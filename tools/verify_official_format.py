#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证Qwen2-VL官方chat_template生成的格式
用于对比我们手动构建的token序列是否符合官方格式
"""

import sys
import io
from transformers import AutoTokenizer

# 设置UTF-8编码以避免Windows控制台编码问题
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

def main():
    # 初始化tokenizer（只需要tokenizer来验证chat_template格式）
    print("正在加载tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained("Qwen/Qwen2-VL-2B-Instruct", trust_remote_code=True)
    print("Tokenizer加载完成\n")
    
    # 构建messages（模拟我们的场景）
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image", "image": "dummy.jpg"},  # 占位符，实际不会处理
                {
                    "type": "text", 
                    "text": "Describe all UI elements in this screenshot, including their types, text content, and bounding box coordinates in JSON format."
                }
            ]
        }
    ]
    
    print("=" * 80)
    print("测试1：不使用generation prompt（ONNX推理应该使用的格式）")
    print("=" * 80)
    
    # 测试1：不使用generation prompt
    text_input_no_prompt = tokenizer.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=False
    )
    print("\n生成的文本格式：")
    print(text_input_no_prompt)
    print()
    
    # Tokenize并查看token信息
    token_ids_no_prompt = tokenizer.encode(
        text_input_no_prompt, add_special_tokens=False
    )
    print(f"Token数量: {len(token_ids_no_prompt)}")
    print(f"\n前30个tokens: {token_ids_no_prompt[:30]}")
    print(f"后30个tokens: {token_ids_no_prompt[-30:]}")
    
    # 解码前30个和后30个tokens，查看内容
    print("\n前30个tokens解码:")
    print(tokenizer.decode(token_ids_no_prompt[:30]))
    print("\n后30个tokens解码:")
    print(tokenizer.decode(token_ids_no_prompt[-30:]))
    
    print("\n" + "=" * 80)
    print("测试2：使用generation prompt（参考，ONNX推理可能不需要）")
    print("=" * 80)
    
    # 测试2：使用generation prompt
    text_input_with_prompt = tokenizer.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=True
    )
    print("\n生成的文本格式：")
    print(text_input_with_prompt)
    print()
    
    token_ids_with_prompt = tokenizer.encode(
        text_input_with_prompt, add_special_tokens=False
    )
    print(f"Token数量: {len(token_ids_with_prompt)}")
    print(f"\n前30个tokens: {token_ids_with_prompt[:30]}")
    print(f"后30个tokens: {token_ids_with_prompt[-30:]}")
    
    print("\n前30个tokens解码:")
    print(tokenizer.decode(token_ids_with_prompt[:30]))
    print("\n后30个tokens解码:")
    print(tokenizer.decode(token_ids_with_prompt[-30:]))
    
    print("\n" + "=" * 80)
    print("关键token ID检查")
    print("=" * 80)
    
    # 检查关键token的ID
    vision_start_id = tokenizer.convert_tokens_to_ids("<|vision_start|>")
    vision_end_id = tokenizer.convert_tokens_to_ids("<|vision_end|>")
    im_start_id = tokenizer.convert_tokens_to_ids("<|im_start|>")
    im_end_id = tokenizer.convert_tokens_to_ids("<|im_end|>")
    
    print(f"<|im_start|> ID: {im_start_id}")
    print(f"<|im_end|> ID: {im_end_id}")
    print(f"<|vision_start|> ID: {vision_start_id}")
    print(f"<|vision_end|> ID: {vision_end_id}")
    
    # 检查这些token在序列中的位置
    print(f"\n<|im_start|>在序列中的位置: {token_ids_no_prompt.index(im_start_id) if im_start_id in token_ids_no_prompt else 'Not found'}")
    print(f"<|vision_start|>在序列中的位置: {token_ids_no_prompt.index(vision_start_id) if vision_start_id in token_ids_no_prompt else 'Not found'}")
    print(f"<|vision_end|>在序列中的位置: {token_ids_no_prompt.index(vision_end_id) if vision_end_id in token_ids_no_prompt else 'Not found'}")
    print(f"<|im_end|>在序列中的位置: {token_ids_no_prompt.index(im_end_id) if im_end_id in token_ids_no_prompt else 'Not found'}")
    
    print("\n" + "=" * 80)
    print("序列结构分析")
    print("=" * 80)
    
    # 分析序列结构
    # 注意：由于我们使用占位符图像，image_pad tokens可能不在序列中
    # 实际使用时，image_pad tokens会被processor自动插入
    
    print("\n✅ 验证完成！")
    print("\n关键发现：")
    print(f"- 不使用generation prompt时，token数量: {len(token_ids_no_prompt)}")
    print(f"- 使用generation prompt时，token数量: {len(token_ids_with_prompt)}")
    print(f"- 差异: {len(token_ids_with_prompt) - len(token_ids_no_prompt)} tokens")
    print("\n注意：由于使用占位符图像，实际的image_pad tokens数量需要根据实际图像特征数量添加（如576个）")

if __name__ == "__main__":
    main()
