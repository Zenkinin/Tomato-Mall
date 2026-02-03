package com.example.tomatomall.service.serviceImpl;

import com.example.tomatomall.dao.ProductRepository;
import com.example.tomatomall.dao.SpecificationRepository;
import com.example.tomatomall.dao.StockpileRepository;
import com.example.tomatomall.po.Product;
import com.example.tomatomall.po.Specification;
import com.example.tomatomall.po.Stockpile;
import com.example.tomatomall.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ProductServiceImpl implements ProductService {

    // 缓存 Key 前缀
    private static final String PRODUCT_CACHE_KEY = "product::";
    private static final String PRODUCT_LIST_CACHE_KEY = "product::list";

    // 缓存时间设置
    private static final Duration CACHE_TTL = Duration.ofMinutes(30); // 正常数据缓存 30 分钟
    private static final Duration NULL_CACHE_TTL = Duration.ofMinutes(5); // 空值数据缓存 5 分钟 (防穿透)

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SpecificationRepository specificationRepository;

    @Autowired
    private StockpileRepository stockpileRepository;

    // [恢复] 注入 Redis 模版 (注意这里用了 @Qualifier 指定名字，对应 Config 里的 Bean)
    @Autowired
    @Qualifier("productRedisTemplate")
    private RedisTemplate<String, Product> productRedisTemplate;

    @Autowired
    @Qualifier("productListRedisTemplate")
    private RedisTemplate<String, List<Product>> productListRedisTemplate;

    // [恢复] 注入 Redisson 客户端 (用于分布式锁)
    @Autowired
    private RedissonClient redissonClient;

    @Override
    public List<Product> getAllProducts() {
        // 列表页通常涉及分页和复杂筛选，简单缓存整个 List 容易由数据不一致。
        // 这里暂时直接查库，或者你可以把 productListRedisTemplate 用起来。
        // 为了演示稳健性，这里保持查库，或者你可以尝试添加缓存逻辑。
        return productRepository.findAllWithSpecifications();
    }

    /**
     * 获取商品详情 (核心重构方法)
     * 逻辑：缓存 -> (未命中) -> 分布式锁 -> 双重检查 -> 数据库 -> 写入缓存
     */
    @Override
    public Product getProduct(Integer id) {
        String key = PRODUCT_CACHE_KEY + id;
        ValueOperations<String, Product> ops = productRedisTemplate.opsForValue();

        // 1. 查询缓存
        Product product = ops.get(key);

        if (product != null) {
            // 1.1 命中空值缓存 (防止缓存穿透)
            if (product.getId() == -1) {
                log.info("命中空值缓存，拦截无效请求，商品ID：{}", id);
                return null;
            }
            // 1.2 命中正常数据
            log.info("从 Redis 缓存中获取商品信息，商品ID：{}", id);
            return product;
        }

        // 2. 缓存未命中，准备查库。为了防止高并发击穿数据库，使用分布式锁
        RLock lock = redissonClient.getLock("lock:product_detail:" + id);
        try {
            // 2.1 尝试加锁
            lock.lock();

            // 2.2 双重检查 (Double Check)
            // 为什么？因为在你等待锁的时候，前一个线程可能已经把数据查出来放进缓存了
            product = ops.get(key);
            if (product != null) {
                if (product.getId() == -1) return null;
                log.info("从 Redis 缓存中获取商品信息 (双重检查命中)，商品ID：{}", id);
                return product;
            }

            // 3. 查询数据库
            product = productRepository.findByIdWithSpecifications(id);

            // 4. 处理数据库结果
            if (product == null) {
                // 4.1 数据库也没数据 -> 缓存一个空对象 (过期时间设置短一点)
                Product nullProduct = new Product();
                nullProduct.setId(-1); // 标记
                ops.set(key, nullProduct, NULL_CACHE_TTL.toMillis(), TimeUnit.MILLISECONDS);
                log.warn("数据库不存在该商品，已写入空值缓存，商品ID：{}", id);
                return null;
            }

            // 4.2 数据库有数据 -> 写入缓存
            // [防雪崩] 设置随机过期时间 (30分钟 + 0~60秒随机)，防止大批缓存同一时间失效
            long randomTtl = CACHE_TTL.toMillis() + (long)(Math.random() * 60000);
            ops.set(key, product, randomTtl, TimeUnit.MILLISECONDS);
            log.info("从数据库加载并写入缓存，商品ID：{}", id);

            return product;

        } finally {
            // 5. 释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public Product createProduct(Product product) {
        // ... (验证和保存逻辑保持不变) ...
        if (product.getTitle() == null || product.getTitle().trim().isEmpty()) {
            throw new RuntimeException("商品标题不能为空");
        }
        if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("商品价格必须大于0");
        }
        product.setId(null);
        if (product.getRate() == null) product.setRate(0.0);

        List<Specification> specs = product.getSpecifications();
        product.setSpecifications(null);
        Product savedProduct = productRepository.save(product);

        if (specs != null) {
            specs.forEach(spec -> {
                spec.setId(null);
                spec.setProduct(savedProduct);
            });
            savedProduct.setSpecifications(specs);
            specificationRepository.saveAll(specs);
            productRepository.save(savedProduct);
        }

        Stockpile stockpile = new Stockpile();
        stockpile.setProductId(savedProduct.getId());
        stockpile.setAmount(0);
        stockpile.setFrozen(0);
        stockpileRepository.save(stockpile);

        // [恢复] 清除列表缓存 (因为新增了数据，列表缓存可能过期)
        Boolean delete = productListRedisTemplate.delete(PRODUCT_LIST_CACHE_KEY);
        log.info("创建商品，清除列表缓存结果: {}", delete);

        return savedProduct;
    }

    @Override
    @Transactional
    public String updateProduct(Product product) {
        if (product.getId() == null) throw new RuntimeException("商品ID不能为空");

        Product existingProduct = productRepository.findById(product.getId())
                .orElseThrow(() -> new RuntimeException("商品不存在"));

        // 更新字段...
        existingProduct.setTitle(product.getTitle());
        existingProduct.setPrice(product.getPrice());
        existingProduct.setRate(product.getRate());
        existingProduct.setDescription(product.getDescription());
        existingProduct.setCover(product.getCover());
        existingProduct.setDetail(product.getDetail());

        specificationRepository.deleteByProductId(product.getId());
        if (product.getSpecifications() != null) {
            for (Specification spec : product.getSpecifications()) {
                spec.setId(null);
                spec.setProduct(existingProduct);
            }
            specificationRepository.saveAll(product.getSpecifications());
            existingProduct.setSpecifications(product.getSpecifications());
        }

        productRepository.save(existingProduct);

        // [恢复] 双删策略：更新数据库后，删除缓存，等待下次查询自动回填
        // 也可以选择直接更新缓存 ops.set(key, product)
        String key = PRODUCT_CACHE_KEY + product.getId();
        productRedisTemplate.delete(key);
        log.info("更新商品，已删除旧缓存，ID：{}", product.getId());

        productListRedisTemplate.delete(PRODUCT_LIST_CACHE_KEY);

        return "更新成功";
    }

    @Override
    @Transactional
    public String deleteProduct(Integer id) {
        Product product = productRepository.findByIdWithSpecifications(id);
        if (product == null) throw new RuntimeException("商品不存在");

        product.getSpecifications().clear();
        productRepository.delete(product);

        // [恢复] 删除缓存
        productRedisTemplate.delete(PRODUCT_CACHE_KEY + id);
        productListRedisTemplate.delete(PRODUCT_LIST_CACHE_KEY);
        log.info("删除商品，清理相关缓存，ID：{}", id);

        return "删除成功";
    }

    @Override
    @Transactional
    public String updateStock(Integer productId, Integer amount) {
        // ... (保持原逻辑) ...
        if (amount == null || amount < 0) throw new RuntimeException("库存不能为负");

        // 这里的逻辑只更新数据库
        // 注意：如果你在 getProduct 里缓存了 Stock 信息，这里也需要删除 product 缓存
        // 但你的 Product 实体里好像没有 Stock 字段（是分开查的），所以可能不需要删 Product 缓存
        Optional<Stockpile> stockpileOptional = stockpileRepository.findByProductId(productId);
        Stockpile stockpile;
        if (stockpileOptional.isPresent()) {
            stockpile = stockpileOptional.get();
            stockpile.setAmount(amount);
        } else {
            stockpile = new Stockpile();
            stockpile.setProductId(productId);
            stockpile.setAmount(amount);
            stockpile.setFrozen(0);
        }
        stockpileRepository.save(stockpile);
        return "库存更新成功";
    }

    @Override
    @Transactional(readOnly = true)
    public Stockpile getStock(Integer productId) {
        // ... (保持原逻辑) ...
        // 库存变化太快，一般不建议长时间缓存，或者使用专门的 redis incr/decr 处理
        productRepository.findById(productId).orElseThrow(() -> new RuntimeException("商品不存在"));
        return stockpileRepository.findByProductId(productId)
                .orElseThrow(() -> new RuntimeException("库存信息不存在"));
    }
}