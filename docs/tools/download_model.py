#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
使用Python API下载Qwen2-VL-2B-Instruct模型（仅下载必需文件）
适用于huggingface-cli命令不可用的情况

使用方法：
    python download_model.py          # 下载Q4F16版本（解码器869MB + 视觉编码器1.33GB + 文本嵌入467MB，推荐）⭐
    python download_model.py --q4f16 # 同上（Q4F16是唯一支持的版本）

注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）
注意：现在包含3个模型文件 + 3个配置文件，总共6个必需文件
"""

import os
from huggingface_hub import hf_hub_download

def download_model(use_q4f16=False):
    下载模型必需文件到当前目录
    
    Args:
        use_q4f16: 始终为True，下载Q4F16版本（解码器869MB + 视觉编码器1.33GB + 文本嵌入467MB）
                   INT8版本不支持（ONNX Runtime Android不支持ConvInteger操作符）
    """
    repo_id = "onnx-community/Qwen2-VL-2B-Instruct"
    local_dir = "."
    
    # 必需文件列表
    required_files = [
        "config.json",
        "preprocessor_config.json",
        "tokenizer.json"
    ]
    
    # 根据选择添加模型文件
    # 注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）
    # 因此只支持Q4F16版本
    decoder_file = "onnx/decoder_model_merged_q4f16.onnx"
    vision_encoder_file = "onnx/vision_encoder_q4f16.onnx"
    embed_tokens_file = "onnx/embed_tokens_q4f16.onnx"  # ⭐ 新增，必需
    
    if use_q4f16 or True:  # 始终使用Q4F16版本（INT8不支持）
        print("📦 下载Q4F16版本（解码器869MB + 视觉编码器1.33GB + 文本嵌入467MB，推荐，兼容性最好）⭐")
        print("⚠️  注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）")
    
    required_files.append(decoder_file)
    required_files.append(vision_encoder_file)
    required_files.append(embed_tokens_file)  # ⭐ 新增
    
    print(f"\n开始下载模型: {repo_id}")
    print(f"保存位置: {os.path.abspath(local_dir)}")
    print(f"将下载 {len(required_files)} 个必需文件")
    print("这可能需要5-15分钟，请耐心等待...\n")
    
    try:
        downloaded_files = []
        
        for file_path in required_files:
            print(f"正在下载: {file_path}...")
            local_file = hf_hub_download(
                repo_id=repo_id,
                filename=file_path,
                local_dir=local_dir,
                local_dir_use_symlinks=False,
                resume_download=True
            )
            downloaded_files.append(local_file)
            print(f"  ✅ 完成: {os.path.basename(local_file)}")
        
        print("\n✅ 所有必需文件下载完成！")
        print(f"\n文件位置: {os.path.abspath(local_dir)}")
        print("\n下载的文件：")
        for f in downloaded_files:
            size = os.path.getsize(f) / (1024 * 1024)  # MB
            print(f"  - {os.path.basename(f)} ({size:.2f} MB)")
        
        return True
        
    except Exception as e:
        print(f"\n❌ 下载失败: {e}")
        return False

if __name__ == "__main__":
    import sys
    
    # 检查是否使用Q4F16版本
    use_q4f16 = "--q4f16" in sys.argv or "-q" in sys.argv
    
    print("使用Q4F16版本（推荐，兼容性最好）⭐\n")
    print("注意：INT8量化版本不支持（ONNX Runtime Android不支持ConvInteger操作符）\n")
    
    download_model(use_q4f16=use_q4f16)
