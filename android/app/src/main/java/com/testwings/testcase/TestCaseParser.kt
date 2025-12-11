package com.testwings.testcase

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 测试用例解析器
 * 从JSON格式解析测试用例
 */
object TestCaseParser {
    
    private const val TAG = "TestCaseParser"
    
    /**
     * 从JSON字符串解析测试用例
     */
    fun parse(jsonString: String): TestCase? {
        return try {
            val json = JSONObject(jsonString)
            parseTestCase(json)
        } catch (e: Exception) {
            Log.e(TAG, "解析测试用例失败", e)
            null
        }
    }
    
    /**
     * 从JSON对象解析测试用例
     */
    fun parseTestCase(json: JSONObject): TestCase {
        val name = json.getString("name")
        val description = json.optString("description", null).takeIf { it.isNotEmpty() }
        val stepsArray = json.getJSONArray("steps")
        
        val steps = mutableListOf<TestStep>()
        for (i in 0 until stepsArray.length()) {
            val stepJson = stepsArray.getJSONObject(i)
            steps.add(parseStep(stepJson, i + 1))
        }
        
        return TestCase(name, description, steps)
    }
    
    /**
     * 解析测试步骤
     */
    private fun parseStep(json: JSONObject, stepNumber: Int): TestStep {
        val description = json.getString("description")
        val actionJson = json.getJSONObject("action")
        val action = parseAction(actionJson)
        val waitAfter = json.optLong("waitAfter", 1000L)
        val verificationJson = json.optJSONObject("verification")
        val verification = verificationJson?.let { parseVerification(it) }
        
        return TestStep(stepNumber, description, action, waitAfter, verification)
    }
    
    /**
     * 解析操作
     */
    private fun parseAction(json: JSONObject): Action {
        val type = json.getString("type")
        
        return when (type) {
            "click" -> {
                val locateBy = LocateBy.valueOf(json.getString("locateBy"))
                val value = json.getString("value")
                Action.Click(locateBy, value)
            }
            "input" -> {
                val locateBy = LocateBy.valueOf(json.getString("locateBy"))
                val locateValue = json.getString("locateValue")
                val text = json.getString("text")
                Action.Input(locateBy, locateValue, text)
            }
            "swipe" -> {
                val direction = SwipeDirection.valueOf(json.getString("direction"))
                val distance = json.optInt("distance", 500)
                Action.Swipe(direction, distance)
            }
            "key" -> {
                val keyType = KeyType.valueOf(json.getString("keyType"))
                Action.Key(keyType)
            }
            "wait" -> {
                val duration = json.getLong("duration")
                Action.Wait(duration)
            }
            "screenshot" -> {
                Action.Screenshot
            }
            else -> {
                throw IllegalArgumentException("未知的操作类型: $type")
            }
        }
    }
    
    /**
     * 解析验证条件
     */
    private fun parseVerification(json: JSONObject): Verification {
        val type = VerificationType.valueOf(json.getString("type"))
        val value = json.getString("value")
        val required = json.optBoolean("required", true)
        
        return Verification(type, value, required)
    }
    
    /**
     * 将测试用例转换为JSON字符串
     */
    fun toJson(testCase: TestCase): String {
        val json = JSONObject()
        json.put("name", testCase.name)
        testCase.description?.let { json.put("description", it) }
        
        val stepsArray = JSONArray()
        testCase.steps.forEach { step ->
            stepsArray.put(toJsonStep(step))
        }
        json.put("steps", stepsArray)
        
        return json.toString(2) // 格式化输出，缩进2个空格
    }
    
    /**
     * 将测试步骤转换为JSON对象
     */
    private fun toJsonStep(step: TestStep): JSONObject {
        val json = JSONObject()
        json.put("description", step.description)
        json.put("action", toJsonAction(step.action))
        json.put("waitAfter", step.waitAfter)
        step.verification?.let { json.put("verification", toJsonVerification(it)) }
        
        return json
    }
    
    /**
     * 将操作转换为JSON对象
     */
    private fun toJsonAction(action: Action): JSONObject {
        val json = JSONObject()
        
        when (action) {
            is Action.Click -> {
                json.put("type", "click")
                json.put("locateBy", action.locateBy.name)
                json.put("value", action.value)
            }
            is Action.Input -> {
                json.put("type", "input")
                json.put("locateBy", action.locateBy.name)
                json.put("locateValue", action.locateValue)
                json.put("text", action.text)
            }
            is Action.Swipe -> {
                json.put("type", "swipe")
                json.put("direction", action.direction.name)
                json.put("distance", action.distance)
            }
            is Action.Key -> {
                json.put("type", "key")
                json.put("keyType", action.keyType.name)
            }
            is Action.Wait -> {
                json.put("type", "wait")
                json.put("duration", action.duration)
            }
            is Action.Screenshot -> {
                json.put("type", "screenshot")
            }
        }
        
        return json
    }
    
    /**
     * 将验证条件转换为JSON对象
     */
    private fun toJsonVerification(verification: Verification): JSONObject {
        val json = JSONObject()
        json.put("type", verification.type.name)
        json.put("value", verification.value)
        json.put("required", verification.required)
        return json
    }
}

