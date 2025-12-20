#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检查ONNX模型的输入节点定义
用于理解模型对输入张量的形状要求
"""

import sys
import io
try:
    import onnx
    import onnxruntime as ort
except ImportError:
    print("错误: 需要安装 onnx 和 onnxruntime")
    print("请运行: pip install onnx onnxruntime")
    sys.exit(1)

# 设置UTF-8编码以避免Windows控制台编码问题
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

def analyze_onnx_model(model_path):
    """分析ONNX模型的输入节点"""
    print(f"正在分析模型: {model_path}")
    print("=" * 80)
    
    # 使用onnx库加载模型
    try:
        model = onnx.load(model_path)
        print("✅ 模型加载成功（使用onnx库）\n")
    except Exception as e:
        print(f"❌ 使用onnx库加载失败: {e}")
        return
    
    # 分析输入节点
    print("=" * 80)
    print("输入节点分析（使用onnx库）")
    print("=" * 80)
    
    graph = model.graph
    inputs = graph.input
    
    print(f"\n输入节点总数: {len(inputs)}\n")
    
    # 分类输入节点
    inputs_embeds_inputs = []
    attention_mask_inputs = []
    position_ids_inputs = []
    past_key_values_inputs = []
    other_inputs = []
    
    for input_tensor in inputs:
        name = input_tensor.name
        shape = [dim.dim_value if dim.dim_value > 0 else (dim.dim_param if dim.dim_param else -1) 
                 for dim in input_tensor.type.tensor_type.shape.dim]
        
        input_info = {
            'name': name,
            'shape': shape,
            'type': input_tensor.type.tensor_type.elem_type
        }
        
        if 'inputs_embeds' in name.lower() or name == 'inputs_embeds':
            inputs_embeds_inputs.append(input_info)
        elif 'attention_mask' in name.lower() or name == 'attention_mask':
            attention_mask_inputs.append(input_info)
        elif 'position_ids' in name.lower() or name == 'position_ids':
            position_ids_inputs.append(input_info)
        elif 'past_key_values' in name.lower() or name.startswith('past_key_values'):
            past_key_values_inputs.append(input_info)
        else:
            other_inputs.append(input_info)
    
    # 打印分类结果
    if inputs_embeds_inputs:
        print("\n【inputs_embeds相关输入】")
        for info in inputs_embeds_inputs:
            shape_str = '[' + ', '.join(str(d) if isinstance(d, int) else f'"{d}"' for d in info['shape']) + ']'
            print(f"  - {info['name']}: 形状={shape_str}")
    
    if attention_mask_inputs:
        print("\n【attention_mask相关输入】")
        for info in attention_mask_inputs:
            shape_str = '[' + ', '.join(str(d) if isinstance(d, int) else f'"{d}"' for d in info['shape']) + ']'
            print(f"  - {info['name']}: 形状={shape_str}")
    
    if position_ids_inputs:
        print("\n【position_ids相关输入】")
        for info in position_ids_inputs:
            shape_str = '[' + ', '.join(str(d) if isinstance(d, int) else f'"{d}"' for d in info['shape']) + ']'
            print(f"  - {info['name']}: 形状={shape_str}")
    
    if past_key_values_inputs:
        print(f"\n【past_key_values相关输入】（共{len(past_key_values_inputs)}个）")
        # 只显示前几个和最后一个
        for i, info in enumerate(past_key_values_inputs[:3]):
            shape_str = '[' + ', '.join(str(d) if isinstance(d, int) else f'"{d}"' for d in info['shape']) + ']'
            print(f"  - {info['name']}: 形状={shape_str}")
        if len(past_key_values_inputs) > 6:
            print(f"  ... (省略{len(past_key_values_inputs) - 6}个) ...")
        for info in past_key_values_inputs[-3:]:
            shape_str = '[' + ', '.join(str(d) if isinstance(d, int) else f'"{d}"' for d in info['shape']) + ']'
            print(f"  - {info['name']}: 形状={shape_str}")
    
    if other_inputs:
        print("\n【其他输入】")
        for info in other_inputs:
            shape_str = '[' + ', '.join(str(d) if isinstance(d, int) else f'"{d}"' for d in info['shape']) + ']'
            print(f"  - {info['name']}: 形状={shape_str}")
    
    # 使用onnxruntime检查输入节点
    print("\n" + "=" * 80)
    print("输入节点分析（使用onnxruntime）")
    print("=" * 80)
    
    try:
        session = ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])
        
        print(f"\n输入节点总数: {len(session.get_inputs())}\n")
        
        for input_meta in session.get_inputs():
            name = input_meta.name
            shape = input_meta.shape
            type_str = input_meta.type
            
            print(f"输入: {name}")
            print(f"  形状: {shape}")
            print(f"  类型: {type_str}")
            
            # 分析动态维度
            dynamic_dims = [i for i, dim in enumerate(shape) if isinstance(dim, str) or (isinstance(dim, int) and dim < 0)]
            if dynamic_dims:
                print(f"  动态维度索引: {dynamic_dims}")
            print()
        
    except Exception as e:
        print(f"❌ 使用onnxruntime加载失败: {e}")
        print("   注意: 这可能是因为模型文件不存在或路径错误")
        print("   请确保模型文件路径正确")

def main():
    import os
    
    # 尝试常见的模型路径
    possible_paths = [
        # Windows路径
        r"E:\AutoTestDemo\TestWings\models\vl\decoder_model_merged_q4f16.onnx",
        r"C:\Users\ch2oh\AppData\Local\Android\Sdk\platform-tools\models\vl\decoder_model_merged_q4f16.onnx",
        # 相对路径
        "models/vl/decoder_model_merged_q4f16.onnx",
        "../models/vl/decoder_model_merged_q4f16.onnx",
    ]
    
    # 如果提供了命令行参数，使用命令行参数
    if len(sys.argv) > 1:
        model_path = sys.argv[1]
    else:
        # 尝试查找模型文件
        model_path = None
        for path in possible_paths:
            if os.path.exists(path):
                model_path = path
                break
        
        if not model_path:
            print("错误: 未找到模型文件")
            print("\n请提供模型文件路径作为命令行参数:")
            print(f"  python {sys.argv[0]} <模型文件路径>")
            print("\n或者将模型文件放在以下位置之一:")
            for path in possible_paths:
                print(f"  - {path}")
            sys.exit(1)
    
    if not os.path.exists(model_path):
        print(f"错误: 模型文件不存在: {model_path}")
        sys.exit(1)
    
    analyze_onnx_model(model_path)

if __name__ == "__main__":
    main()
