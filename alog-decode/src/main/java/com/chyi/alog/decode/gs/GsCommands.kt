package com.chyi.alog.decode.gs

/**
 * Command names / STA / Drink_NO tables from GS_CF_PR_API_1.5F.
 */
object GsCommands {
    private val NAMES = mapOf(
        0x01 to "制作一杯指定饮品",
        0x04 to "设置热饮的温度门限值",
        0x05 to "设置冷饮的温度门限值",
        0x06 to "读取指定饮料的销售杯数",
        0x07 to "设置咖啡机出杯方式",
        0x08 to "咖啡机落杯功能检测",
        0x09 to "咖啡机自动全检",
        0x0A to "清洗所有速溶管道",
        0x0B to "查询咖啡机状态",
        0x0C to "查询错误代码",
        0x0E to "设置机器本地指定饮料的价格",
        0x0F to "查询咖啡机余额",
        0x10 to "执行找零操作",
        0x12 to "清洗指定速溶配方管道",
        0x15 to "设置指定饮料的出水出料配方时间",
        0x17 to "现磨咖啡机制作完成通知",
        0x19 to "查询制冰机状态",
        0x1A to "单元功能测试",
        0x1B to "电控锁操作",
        0x1C to "补水操作",
        0x1D to "配置指定饮料制作流程",
        0x1E to "查询主控运行状态",
        0x1F to "制作状态查询",
        0x20 to "取得指定模组指定信息(透传)",
        0x21 to "设置指定模组指定信息(透传)",
        0x22 to "取得指定对象最后一次异常信息",
        0x23 to "强制停止任务指令",
        0x24 to "送杯指令",
        0x25 to "速溶单通道执行指令",
        0x26 to "重启/复位指令",
        0x27 to "整机制饮管路清洗指令",
        0x28 to "主动上报对象异常",
        0x29 to "排空水路",
        0x2A to "取得多落杯器状态信息",
        0x2B to "取得指定落杯器内部详情",
        0x2C to "取得控制板基本信息",
    )

    private val STA = mapOf(
        0x00 to "设备正常/操作成功",
        0x01 to "设备忙",
        0x02 to "设备故障/执行失败",
        0x03 to "参数错误",
        0x04 to "余额不足",
        0x05 to "配置参数限制了命令功能",
        0x06 to "数据/配方不存在或无效",
        0x07 to "当前不满足执行条件",
        0x08 to "指定的设备/部件/功能不存在",
        0x7F to "控制板主动上报",
    )

    fun nameOf(command: Int): String = NAMES[command] ?: "未知指令"

    fun staOf(code: Int): String = STA[code] ?: "未知STA(0x%02X)".format(code)

    fun drinkNo(code: Int): String = when (code) {
        in 0x01..0x07 -> "热饮$code"
        in 0x11..0x17 -> "冷饮${code - 0x10}"
        else -> "Drink_NO=0x%02X".format(code)
    }

    fun moduleAddr(addr: Int): String = when (addr) {
        0x01 -> "主控"
        0x02 -> "前门模组"
        0x03 -> "制冰模组"
        0x04 -> "现磨模组"
        0x05 -> "萃茶模组"
        0x06 -> "苏打模组"
        0x80 -> "小料模组"
        else -> "ADDR=0x%02X".format(addr)
    }

    fun modbusEcode(code: Int): String = when (code) {
        0x01 -> "非法功能码"
        0x02 -> "无效寄存器地址"
        0x03 -> "无效数据值"
        0x04 -> "设备故障"
        0x05 -> "请求已接受但需长时间处理"
        0x07 -> "设备正忙"
        0xFC -> "指令透传/发送失败"
        0xFD -> "模组离线或超时未应答"
        0xFE -> "指定模组对象不存在"
        else -> "ECODE=0x%02X".format(code)
    }
}
