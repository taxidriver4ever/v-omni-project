import os
import sys
from minio import Minio
from minio.error import S3Error

# 优先从环境变量读取配置，若无则使用默认值（适合容器化部署）
MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "localhost:9000")
ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "admin")
SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "password123")
USE_SECURE = os.getenv("MINIO_USE_SECURE", "false").lower() == "true"
TEST_BUCKET = os.getenv("TEST_BUCKET", "tmp-extraction-image")
TEST_PREFIX = os.getenv("TEST_PREFIX", "frames/")   # 可选前缀

def test_minio_connection():
    try:
        # 创建 MinIO 客户端
        client = Minio(
            MINIO_ENDPOINT,
            access_key=ACCESS_KEY,
            secret_key=SECRET_KEY,
            secure=USE_SECURE,
            http_client=None   # 可在此添加超时配置
        )
        print(f"✅ 客户端创建成功，连接端点: {MINIO_ENDPOINT} (安全模式: {USE_SECURE})")

        # 列出所有桶
        buckets = client.list_buckets()
        print("\n📦 所有桶:")
        for bucket in buckets:
            print(f"   - {bucket.name} (创建时间: {bucket.creation_date})")

        # 测试指定桶是否存在
        if not client.bucket_exists(TEST_BUCKET):
            print(f"\n❌ 桶 '{TEST_BUCKET}' 不存在")
            sys.exit(1)

        print(f"\n✅ 桶 '{TEST_BUCKET}' 存在")

        # 列出桶中指定前缀的对象
        print(f"\n📄 桶 '{TEST_BUCKET}' 中以 '{TEST_PREFIX}' 开头的对象:")
        objects = list(client.list_objects(TEST_BUCKET, prefix=TEST_PREFIX, recursive=True))
        if not objects:
            print("   没有找到匹配的对象")
        else:
            total_size = 0
            for i, obj in enumerate(objects):
                print(f"   [{i+1}] {obj.object_name} (大小: {obj.size} bytes, 最后修改: {obj.last_modified})")
                total_size += obj.size
                if i >= 9:   # 只显示前10个
                    remaining = len(objects) - 10
                    if remaining > 0:
                        print(f"   ... 还有 {remaining} 个对象未显示")
                    break
            print(f"\n📊 该前缀下共有 {len(objects)} 个对象，总大小: {total_size / (1024*1024):.2f} MB")

            # 测试读取第一个文件的权限（可选）
            first_obj = objects[0]
            try:
                response = client.get_object(TEST_BUCKET, first_obj.object_name)
                data = response.read(1024)  # 只读前1KB验证权限
                response.close()
                response.release_conn()
                print(f"\n🔍 成功读取第一个文件 '{first_obj.object_name}' 的前 {len(data)} 字节，读取权限正常")
            except Exception as read_err:
                print(f"\n⚠️ 读取文件 '{first_obj.object_name}' 失败: {read_err}")

        print("\n🎉 连通性测试完成！")

    except S3Error as e:
        print(f"\n❌ MinIO 操作失败 (S3Error): {e}")
        print("   请检查 access_key / secret_key 是否正确，以及桶是否存在。")
        sys.exit(1)
    except Exception as e:
        print(f"\n❌ 未知错误: {e}")
        sys.exit(1)

if __name__ == "__main__":
    test_minio_connection()