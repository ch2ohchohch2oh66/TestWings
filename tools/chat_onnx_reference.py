#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
qwen2-export-onnx的chat_onnx.py代码（参考）
用于对比我们的Android实现与Python实现的差异
"""

# 关键代码片段：

# 1. past_key_values初始化（第一次推理）
def get_reply_with_implicit_history(new_user_content):
    # ...
    # Define(clear) the KV, because using the previous KV directly will lead to onnx err
    past_key_values = []
    for i in range(2*n_layer):
        past_key_values.append(np.zeros((1, n_kv_heads, 0, dim_head), np.float16))
    # ⚠️ 关键：这里使用seq_len=0！

# 2. generate函数
def generate(input_ids, past_key_values):
    inputs = {
        "input_ids": input_ids,
        "attention_mask": np.ones_like(input_ids),  # ⚠️ 关键：长度与input_ids一致
    }
    for i in range(n_layer):
        key_index = 2 * i
        value_index = key_index + 1
        inputs["past_key_in" + str(i)] = past_key_values[key_index]
        inputs["past_value_in" + str(i)] = past_key_values[value_index]
    outputs = session.run(None, inputs)
    return logits_session.run(None, {
        "all_input_ids": input_ids,
        "logits": outputs[0][:, -1:, :],
    }), outputs[1:]
