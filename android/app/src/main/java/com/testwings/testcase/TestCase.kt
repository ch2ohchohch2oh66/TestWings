package com.testwings.testcase

/**
 * 测试用例数据类
 */
data class TestCase(
    /**
     * 测试用例名称
     */
    val name: String,
    
    /**
     * 测试用例描述
     */
    val description: String? = null,
    
    /**
     * 测试步骤列表
     */
    val steps: List<TestStep>
)

/**
 * 测试步骤
 */
data class TestStep(
    /**
     * 步骤序号（从1开始）
     */
    val stepNumber: Int,
    
    /**
     * 步骤描述
     */
    val description: String,
    
    /**
     * 操作类型
     */
    val action: Action,
    
    /**
     * 等待时间（毫秒），操作执行后等待的时间
     */
    val waitAfter: Long = 1000L,
    
    /**
     * 验证条件（可选）
     */
    val verification: Verification? = null
)

/**
 * 操作类型
 */
sealed class Action {
    /**
     * 点击操作
     */
    data class Click(
        /**
         * 定位方式
         */
        val locateBy: LocateBy,
        
        /**
         * 定位值（文本、坐标等）
         */
        val value: String
    ) : Action()
    
    /**
     * 输入操作
     */
    data class Input(
        /**
         * 定位方式
         */
        val locateBy: LocateBy,
        
        /**
         * 定位值（用于找到输入框）
         */
        val locateValue: String,
        
        /**
         * 要输入的文本
         */
        val text: String
    ) : Action()
    
    /**
     * 滑动操作
     */
    data class Swipe(
        /**
         * 滑动方向
         */
        val direction: SwipeDirection,
        
        /**
         * 滑动距离（像素，可选，默认500）
         */
        val distance: Int = 500
    ) : Action()
    
    /**
     * 系统按键操作
     */
    data class Key(
        /**
         * 按键类型
         */
        val keyType: KeyType
    ) : Action()
    
    /**
     * 等待操作（等待指定时间）
     */
    data class Wait(
        /**
         * 等待时间（毫秒）
         */
        val duration: Long
    ) : Action()
    
    /**
     * 截图操作（用于调试和报告）
     */
    object Screenshot : Action()
}

/**
 * 定位方式
 */
enum class LocateBy {
    /**
     * 根据文本定位（使用OCR识别结果）
     */
    TEXT,
    
    /**
     * 根据坐标定位（格式：x,y）
     */
    COORDINATE,
    
    /**
     * 根据资源ID定位（使用Accessibility Service）
     */
    RESOURCE_ID
}

/**
 * 滑动方向
 */
enum class SwipeDirection {
    UP,    // 向上
    DOWN,  // 向下
    LEFT,  // 向左
    RIGHT  // 向右
}

/**
 * 系统按键类型
 */
enum class KeyType {
    BACK,   // 返回键
    HOME,   // 主页键
    RECENT  // 最近任务键
}

/**
 * 验证条件
 */
data class Verification(
    /**
     * 验证类型
     */
    val type: VerificationType,
    
    /**
     * 验证值（根据类型不同，含义不同）
     */
    val value: String,
    
    /**
     * 是否必须验证（true：验证失败则测试失败；false：验证失败仅记录警告）
     */
    val required: Boolean = true
)

/**
 * 验证类型
 */
enum class VerificationType {
    /**
     * 验证文本存在（使用OCR识别，检查屏幕上是否包含指定文本）
     */
    TEXT_EXISTS,
    
    /**
     * 验证文本不存在（使用OCR识别，检查屏幕上是否不包含指定文本）
     */
    TEXT_NOT_EXISTS,
    
    /**
     * 验证元素存在（使用Accessibility Service，检查元素是否存在）
     */
    ELEMENT_EXISTS,
    
    /**
     * 验证元素不存在（使用Accessibility Service，检查元素是否不存在）
     */
    ELEMENT_NOT_EXISTS
}

