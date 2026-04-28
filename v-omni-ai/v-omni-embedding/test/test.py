import easyocr
from pathlib import Path


def debug_ocr_format():
    # 1. 初始化 Reader (确保和 server.py 一致)
    print("正在初始化 EasyOCR...")
    reader = easyocr.Reader(['ch_sim', 'en'], gpu=True)  # 如果没显卡改成 False

    # 2. 找到你报错的帧 (指向你之前生成的帧目录)
    # 提示：你可以在 D:\private_project\v-omni-project\v-omni-ai\v-omni-embedding\ 下找找 temp 目录
    # 或者随便找张带字的图片放进来
    test_image = "frame_0001.jpg"

    if not Path(test_image).exists():
        print(f"错误: 找不到测试图片 {test_image}，请放一张带字的图并改名。")
        return

    print(f"--- 开始测试图片: {test_image} ---")

    try:
        # detail=1 是关键，它决定了返回值的维度
        results = reader.readtext(test_image, detail=1)

        print(f"OCR 返回结果数量: {len(results)}")

        if len(results) > 0:
            first_res = results[0]
            print(f"原始返回的第一条数据内容: {first_res}")
            print(f"数据类型: {type(first_res)}")
            print(f"元组长度: {len(first_res)}")

            # 尝试各种解包方式
            print("\n--- 尝试解包 ---")
            if len(first_res) == 3:
                box, text, conf = first_res
                print(f"解包成功 (3值): 文字='{text}', 置信度={conf}")
            elif len(first_res) == 2:
                text, conf = first_res
                print(f"解包成功 (2值): 文字='{text}', 置信度={conf}")
            else:
                print(f"解析失败: 返回了意料之外的长度 {len(first_res)}")

        else:
            print("未能识别到任何文字。")

    except Exception as e:
        print(f"捕获到异常: {type(e).__name__}: {e}")


if __name__ == "__main__":
    debug_ocr_format()