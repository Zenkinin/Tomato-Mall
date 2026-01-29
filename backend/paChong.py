# -*- coding: utf-8 -*-
import pymysql
import random
import time
from pymysql import cursors

# ================= 配置 =================
DB_CONFIG = {
    'host': 'localhost',
    'port': 3307,
    'database': 'tomatomall',
    'user': 'root',
    'password': 'root',
    'charset': 'utf8mb4',
    'cursorclass': cursors.DictCursor
}

# 预设用户数据 (就是你 SQL 里的那 10 个人)
# 密码统一为 123456 的加密串
DEFAULT_PASSWORD = '$2a$10$mAvRDmcltj4kJ.uWXkNmYekY7jmoRqBYMvi/DSC0Yy4Hib6fL3a8K'

INITIAL_USERS = [
    {'username': 'Zhang San', 'name': 'Zhang San', 'role': 'user', 'phone': '13312345678'},
    {'username': 'cz', 'name': 'cz', 'role': 'user', 'phone': '13291770128'},
    {'username': 'admin', 'name': 'admin', 'role': 'admin', 'phone': '12312345678'},
    {'username': 'student', 'name': 'student', 'role': 'user', 'phone': '13300000001'},
    {'username': 'david', 'name': 'David Wilson', 'role': 'user', 'phone': '13544444445'},
    {'username': 'sarah', 'name': 'Sarah Miller', 'role': 'user', 'phone': '13455555556'},
    {'username': 'kevin', 'name': 'Kevin Anderson', 'role': 'merchant', 'phone': '13366666667'},
    {'username': 'laura', 'name': 'Laura Thomas', 'role': 'user', 'phone': '13277777778'},
    {'username': 'chris', 'name': 'Chris Jackson', 'role': 'user', 'phone': '13188888889'},
    {'username': 'amanda', 'name': 'Amanda White', 'role': 'user', 'phone': '13099999990'}
]

# 预设书籍数据
TITLES_POOL = {
    '计算机': ['Java编程思想', 'SpringBoot实战', '深入理解计算机系统', '算法导论', '黑客与画家', '高性能MySQL', 'Redis设计与实现', 'Kubernetes权威指南'],
    '小说': ['活着', '三体', '百年孤独', '解忧杂货店', '追风筝的人', '白夜行', '嫌疑人X的献身', '平凡的世界'],
    '经济': ['置身事内', '贫穷的本质', '激荡三十年', '大败局', '经济学原理', '富爸爸穷爸爸', '非理性繁荣'],
    '历史': ['明朝那些事儿', '万历十五年', '人类简史', '中国历代政治得失', '枪炮、病菌与钢铁', '大秦帝国']
}

COVERS = [
    "https://img9.doubanio.com/view/subject/s/public/s34041794.jpg",
    "https://img1.doubanio.com/view/subject/s/public/s28359308.jpg",
    "https://img2.doubanio.com/view/subject/s/public/s33842492.jpg",
    "https://img9.doubanio.com/view/subject/s/public/s29663736.jpg",
    "https://img1.doubanio.com/view/subject/s/public/s27685328.jpg"
]

COMMENTS_POOL = [
    "快递很快，书也是正版。", "内容太棒了，受益匪浅！", "给孩子买的，很喜欢。",
    "印刷质量一般，但内容是好书。", "经典就是经典，值得收藏。", "看不懂，太深奥了。",
    "朋友推荐的，果然不错。", "包装有点破损，希望能改进。"
]

def get_connection():
    return pymysql.connect(**DB_CONFIG)

def init_users(cursor):
    """初始化用户数据，返回所有可用的用户ID列表"""
    print(">>> 正在检查/初始化用户数据...")
    valid_ids = []

    for user in INITIAL_USERS:
        # 1. 检查用户是否存在（通过 username）
        cursor.execute("SELECT userid FROM users WHERE username = %s", (user['username'],))
        result = cursor.fetchone()

        if result:
            # 用户已存在，直接记录ID
            valid_ids.append(result['userid'])
        else:
            # 用户不存在，执行插入
            # 注意：avatar, email, location 给默认值或留空，防止报错
            sql = """
                INSERT INTO users (username, password, name, telephone, role, avatar, email, location)
                VALUES (%s, %s, %s, %s, %s, '', '', '')
            """
            cursor.execute(sql, (
                user['username'],
                DEFAULT_PASSWORD,
                user['name'],
                user['phone'],
                user['role']
            ))
            new_id = cursor.lastrowid
            valid_ids.append(new_id)
            print(f"  + 已创建用户: {user['username']}")

    return valid_ids

def generate_mock_data():
    conn = get_connection()
    try:
        with conn.cursor() as cursor:
            # Step 1: 初始化用户 (核心修改点)
            user_ids = init_users(cursor)
            if not user_ids:
                raise Exception("用户初始化失败，无法继续生成评论！")
            print(f"✅ 用户就绪，可用ID数: {len(user_ids)}\n")

            # Step 2: 初始化书籍及相关数据
            print(">>> 开始生成书籍数据...")
            total_added = 0

            for category, titles in TITLES_POOL.items():
                print(f"正在处理分类: {category}")

                for title in titles:
                    # 检查是否已存在
                    cursor.execute("SELECT id FROM products WHERE title = %s", (title,))
                    if cursor.fetchone():
                        continue

                    # 插入商品
                    price = round(random.uniform(30.0, 120.0), 2)
                    rate = round(random.uniform(7.0, 9.9), 1)
                    cover = random.choice(COVERS)
                    desc = f"这是一本关于{category}的经典著作，深受读者喜爱。"

                    cursor.execute("""
                        INSERT INTO products (title, price, rate, description, cover, detail)
                        VALUES (%s, %s, %s, %s, %s, %s)
                    """, (title, price, rate, desc, cover, desc * 5))

                    product_id = cursor.lastrowid

                    # 插入规格
                    cursor.execute("""
                        INSERT INTO specifications (item, value, product_id)
                        VALUES (%s, %s, %s), (%s, %s, %s)
                    """, ('出版社', '人民邮电出版社', product_id, '作者', '佚名', product_id))

                    # 插入库存
                    cursor.execute("""
                        INSERT INTO stockpiles (product_id, amount, frozen, locked_amount)
                        VALUES (%s, %s, 0, 0)
                    """, (product_id, random.randint(50, 200)))

                    # 插入广告
                    if random.random() < 0.2:
                        cursor.execute("""
                            INSERT INTO advertisements (title, content, image_url, product_id)
                            VALUES (%s, %s, %s, %s)
                        """, ('好书推荐', title, cover, product_id))

                    # 插入评论 (使用刚才初始化的 user_ids)
                    for _ in range(random.randint(1, 4)):
                        real_uid = random.choice(user_ids)
                        cursor.execute("""
                            INSERT INTO comments (product_id, user_id, content, rating, create_time, update_time, status)
                            VALUES (%s, %s, %s, %s, NOW(), NOW(), 1)
                        """, (product_id, real_uid, random.choice(COMMENTS_POOL), round(random.uniform(3.0, 5.0), 1)))

                    total_added += 1
                    print(f"  + 已添加图书: {title}")

            conn.commit()
            print(f"\n✅ 全部完成！用户数据已补全，并新增了 {total_added} 本图书。")

    except Exception as e:
        print(f"❌ 发生错误: {e}")
        conn.rollback()
    finally:
        conn.close()

if __name__ == "__main__":
    generate_mock_data()