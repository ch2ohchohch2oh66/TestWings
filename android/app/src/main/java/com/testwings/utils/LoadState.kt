package com.testwings.utils

/**
 * 模型加载状态
 */
enum class LoadState {
    /**
     * 未加载
     */
    NOT_LOADED,
    
    /**
     * 加载中
     */
    LOADING,
    
    /**
     * 已加载
     */
    LOADED,
    
    /**
     * 加载失败
     */
    FAILED
}
