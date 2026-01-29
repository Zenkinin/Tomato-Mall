package com.example.tomatomall.service.serviceImpl;

import com.example.tomatomall.dao.ProductRepository;
import com.example.tomatomall.dao.SpecificationRepository;
import com.example.tomatomall.dao.StockpileRepository;
import com.example.tomatomall.po.Product;
import com.example.tomatomall.po.Specification;
import com.example.tomatomall.po.Stockpile;
import com.example.tomatomall.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

// [重构说明] 已移除所有 Redis/Redisson 依赖，改为纯数据库操作
@Service
@Slf4j
public class ProductServiceImpl implements ProductService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SpecificationRepository specificationRepository;

    @Autowired
    private StockpileRepository stockpileRepository;

    // [已删除] RedisTemplate 和 RedissonClient 的注入

    @Override
    public List<Product> getAllProducts() {
        // 直接查库
        return productRepository.findAllWithSpecifications();
    }

    @Override
    public Product getProduct(Integer id) {
        // [重构] 删除缓存逻辑，直接查库
        Product product = productRepository.findByIdWithSpecifications(id);
        if (product == null) {
            log.warn("商品不存在，ID：{}", id);
            return null; // 或者抛出异常，视业务而定
        }
        log.info("从数据库获取商品信息，商品ID：{}", id);
        return product;
    }

    @Override
    @Transactional
    public Product createProduct(Product product) {
        // 基本验证
        if (product.getTitle() == null || product.getTitle().trim().isEmpty()) {
            throw new RuntimeException("商品标题不能为空");
        }
        if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("商品价格必须大于0");
        }

        // 设置新商品
        product.setId(null);
        if (product.getRate() == null) {
            product.setRate(0.0);
        }

        // 处理规格信息
        List<Specification> specs = product.getSpecifications();
        product.setSpecifications(null);

        // 先保存商品
        Product savedProduct = productRepository.save(product);

        // 处理规格
        if (specs != null) {
            specs.forEach(spec -> {
                spec.setId(null);
                spec.setProduct(savedProduct);
            });
            savedProduct.setSpecifications(specs);
            specificationRepository.saveAll(specs);
            productRepository.save(savedProduct);
        }

        // 创建库存记录
        Stockpile stockpile = new Stockpile();
        stockpile.setProductId(savedProduct.getId());
        stockpile.setAmount(0);
        stockpile.setFrozen(0);
        stockpileRepository.save(stockpile);

        // [已删除] 清除缓存逻辑
        return savedProduct;
    }

    @Override
    @Transactional
    public String updateProduct(Product product) {
        if (product.getId() == null) {
            throw new RuntimeException("商品ID不能为空");
        }

        // 检查商品是否存在
        Product existingProduct = productRepository.findById(product.getId())
                .orElseThrow(() -> new RuntimeException("商品不存在"));

        // 更新基本信息
        existingProduct.setTitle(product.getTitle());
        existingProduct.setPrice(product.getPrice());
        existingProduct.setRate(product.getRate());
        existingProduct.setDescription(product.getDescription());
        existingProduct.setCover(product.getCover());
        existingProduct.setDetail(product.getDetail());

        // 删除旧的规格信息
        specificationRepository.deleteByProductId(product.getId());

        // 添加新的规格信息
        if (product.getSpecifications() != null) {
            for (Specification spec : product.getSpecifications()) {
                spec.setId(null); // 确保是新建规格
                spec.setProduct(existingProduct); // 只设置 product 关联
            }
            specificationRepository.saveAll(product.getSpecifications());
            existingProduct.setSpecifications(product.getSpecifications());
        }

        // 保存更新
        productRepository.save(existingProduct);

        // [已删除] 更新缓存逻辑
        log.info("更新商品成功，商品ID：{}", product.getId());
        return "更新成功";
    }

    @Override
    @Transactional
    public String deleteProduct(Integer id) {
        // 检查商品是否存在
        Product product = productRepository.findByIdWithSpecifications(id);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        // 清空规格关联
        product.getSpecifications().clear();

        // 删除商品（由于配置了 cascade = CascadeType.ALL，规格会自动删除）
        productRepository.delete(product);

        // [已删除] 删除缓存逻辑
        log.info("删除商品成功，商品ID：{}", id);
        return "删除成功";
    }

    @Override
    @Transactional
    public String updateStock(Integer productId, Integer amount) {
        // 验证参数
        if (amount == null || amount < 0) {
            throw new RuntimeException("库存数量不能为负数");
        }

        // 检查商品是否存在
        productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("商品不存在"));

        // 获取并更新库存信息
        Optional<Stockpile> stockpileOptional = stockpileRepository.findByProductId(productId);
        Stockpile stockpile;
        if (stockpileOptional.isPresent()) {
            stockpile = stockpileOptional.get();
            stockpile.setAmount(amount);
        } else {
            // 如果库存记录不存在，创建新的库存记录
            stockpile = new Stockpile();
            stockpile.setProductId(productId);
            stockpile.setAmount(amount);
            stockpile.setFrozen(0);
        }

        // 保存库存更新
        stockpileRepository.save(stockpile);
        return "库存更新成功";
    }

    @Override
    @Transactional(readOnly = true)
    public Stockpile getStock(Integer productId) {
        // 检查商品是否存在
        productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("商品不存在"));

        // 查询商品库存信息
        Optional<Stockpile> stockpile = stockpileRepository.findByProductId(productId);
        if (stockpile.isPresent()) {
            return stockpile.get();
        } else {
            throw new RuntimeException("库存信息不存在");
        }
    }
}