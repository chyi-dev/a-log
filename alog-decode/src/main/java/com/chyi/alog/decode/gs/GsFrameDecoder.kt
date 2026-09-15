package com.chyi.alog.decode.gs

/**
 * Decode GS frame DATA by COMND (GS_CF_PR_API_1.5F). Passthrough 0x20/0x21 delegated.
 */
object GsFrameDecoder {
    fun decode(frame: GsFrame): List<String> {
        val dir = if (frame.isCommand) "发送" else "接收"
        val name = GsCommands.nameOf(frame.command)
        val sumText = if (frame.checksumOk) {
            "SUM=ok"
        } else {
            "SUM=不符(期望%02X 实际%02X)".format(frame.expectedSum, frame.sum)
        }
        val header = "[$dir] 0x%02X $name  LEN=${frame.len} $sumText".format(frame.command)
        val body = when (frame.command) {
            0x20, 0x21 -> GsPassthrough.decode(frame)
            else -> decodePrimary(frame)
        }
        return listOf(header) + body
    }

    private fun decodePrimary(frame: GsFrame): List<String> {
        val d = frame.data
        val cmd = frame.command
        return when {
            frame.isCommand -> decodeCommand(cmd, d)
            else -> decodeResponse(cmd, d)
        }
    }

    private fun decodeCommand(cmd: Int, d: ByteArray): List<String> = when (cmd) {
        0x01 -> if (d.size >= 2) listOf(
            "Drink_NO=${GsCommands.drinkNo(GsBytes.u8(d, 0))}",
            "LocalOrCmd=" + when (GsBytes.u8(d, 1)) {
                0x01 -> "本机余额消费"
                0x02 -> "命令直接制饮"
                else -> "0x%02X".format(GsBytes.u8(d, 1))
            },
        ) else short(d)

        0x04, 0x05 -> if (d.size >= 2) {
            val kind = if (cmd == 0x04) "热饮" else "冷饮"
            listOf("${kind}上限=${GsBytes.u8(d, 0)}℃", "${kind}下限=${GsBytes.u8(d, 1)}℃")
        } else short(d)

        0x06, 0x12 -> if (d.isNotEmpty()) listOf("Drink_NO=${GsCommands.drinkNo(GsBytes.u8(d, 0))}") else emptyList()

        0x07 -> if (d.isNotEmpty()) listOf(
            "CUP=" + when (GsBytes.u8(d, 0)) {
                0 -> "自动落杯"
                1 -> "手动放杯"
                else -> "0x%02X".format(GsBytes.u8(d, 0))
            },
        ) else emptyList()

        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0F, 0x1E, 0x1F, 0x2A -> emptyList()

        0x0E -> if (d.size >= 2) listOf(
            "Drink_NO=${GsCommands.drinkNo(GsBytes.u8(d, 0))}",
            "价格=${GsBytes.u8(d, 1)}",
        ) else short(d)

        0x10 -> if (d.isNotEmpty()) listOf("找零参数=0x%02X".format(GsBytes.u8(d, 0))) else emptyList()

        0x15 -> decode015Command(d)
        0x17 -> if (d.isNotEmpty()) listOf("通知参数=${GsBytes.hex(d)}") else emptyList()
        0x19 -> emptyList()
        0x1A -> decode1A(d)
        0x1B -> decode1B(d)
        0x1C -> if (d.isNotEmpty()) listOf("补水参数=${GsBytes.hex(d)}") else emptyList()
        0x1D -> decode1D(d)
        0x22 -> if (d.size >= 3) listOf(
            "保留=0x%02X".format(GsBytes.u8(d, 0)),
            "OBJ_NO=0x%04X".format(GsBytes.u16be(d, 1)),
        ) else short(d)

        0x23 -> if (d.isNotEmpty()) listOf(
            "停止目标=" + when (GsBytes.u8(d, 0)) {
                0x01 -> "强制停止制饮流程"
                0x24 -> "强制停止送杯流程"
                else -> "0x%02X".format(GsBytes.u8(d, 0))
            },
        ) else emptyList()

        0x24 -> if (d.size >= 2) listOf(
            "保留=0x%02X".format(GsBytes.u8(d, 0)),
            "等待取杯=${GsBytes.u8(d, 1)}S",
        ) else short(d)

        0x25 -> decode025(d)
        0x26 -> if (d.size >= 2) decode026(GsBytes.u16be(d, 0)) else short(d)
        0x27 -> decode027(d)
        0x29 -> decode029Command(d)
        0x2B -> if (d.isNotEmpty()) listOf("落杯器索引=${GsBytes.u8(d, 0)}") else emptyList()
        0x2C -> if (d.isNotEmpty()) listOf(
            "PCBAT=" + when (GsBytes.u8(d, 0)) {
                1 -> "主控速溶板信息"
                else -> "0x%02X".format(GsBytes.u8(d, 0))
            },
        ) else emptyList()

        else -> if (d.isNotEmpty()) listOf("DATA=${GsBytes.hex(d)}") else emptyList()
    }

    private fun decodeResponse(cmd: Int, d: ByteArray): List<String> = when (cmd) {
        0x01, 0x04, 0x05, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0E, 0x10, 0x12, 0x15, 0x17,
        0x1A, 0x1B, 0x1C, 0x1D, 0x23, 0x24, 0x25, 0x26, 0x27,
        -> staOnly(d)

        0x06 -> if (d.size >= 9) listOf(
            "Drink_NO=${GsCommands.drinkNo(GsBytes.u8(d, 0))}",
            "本地销售杯数=${GsBytes.u32be(d, 1)}",
            "指令制作杯数=${GsBytes.u32be(d, 5)}",
        ) else short(d)

        0x0C -> decode0C(d)
        0x0F -> if (d.isNotEmpty()) listOf("余额=${GsBytes.u8(d, 0)}") else emptyList()
        0x19 -> decode019(d)
        0x1E -> decode1E(d)
        0x1F -> decode1F(d)
        0x22, 0x28 -> decode022(d)
        0x29 -> decode029Response(d)
        0x2A -> decode02A(d)
        0x2B -> decode02B(d)
        0x2C -> decode02C(d)
        else -> if (d.isNotEmpty()) listOf("DATA=${GsBytes.hex(d)}") else emptyList()
    }

    private fun staOnly(d: ByteArray): List<String> =
        if (d.isNotEmpty()) listOf("STA=${GsCommands.staOf(GsBytes.u8(d, 0))}") else emptyList()

    private fun short(d: ByteArray): List<String> =
        if (d.isEmpty()) listOf("DATA长度不足") else listOf("DATA=${GsBytes.hex(d)} (长度不足)")

    private fun decode0C(d: ByteArray): List<String> {
        if (d.isEmpty()) return emptyList()
        val code = GsBytes.u8(d, 0)
        val fixed = when (code) {
            0x05 -> "落杯成功"
            0x10 -> "饮料制作完成"
            0x06 -> "落冰完成"
            0x20 -> "轨道异物/压盖失败/前门模组离线"
            else -> null
        }
        if (fixed != null) return listOf("制饮主动上报: $fixed (ErrorCode=0x%02X)".format(code))
        val flags = listOf(
            0 to "缺水",
            1 to "缺杯",
            2 to "缺水且缺杯",
            3 to "传感器故障",
            5 to "轨道故障",
            6 to "无盖",
            7 to "设备通电首次加热",
        )
        return formatStatusBits("ErrorCode", code.toLong(), null, flags)
    }

    private fun decode015Command(d: ByteArray): List<String> {
        if (d.size < 3) return short(d)
        return listOf(
            "Drink_NO=${GsCommands.drinkNo(GsBytes.u8(d, 0))}",
            "配方DATA=${GsBytes.hex(d, 1)}",
        )
    }

    private fun decode1A(d: ByteArray): List<String> {
        if (d.isEmpty()) return emptyList()
        val testCmd = GsBytes.u8(d, 0)
        val d1 = if (d.size > 1) GsBytes.u8(d, 1) else null
        val d2 = if (d.size > 2) GsBytes.u8(d, 2) else null
        val d3 = if (d.size > 3) GsBytes.u8(d, 3) else null
        val lines = mutableListOf<String>()
        lines += when (testCmd) {
            0x01 -> "TestCmd=出料功能测试"
            0x02 -> "TestCmd=坐标工位测试"
            0x03 -> "TestCmd=前门模组功能测试"
            0x04 -> "TestCmd=制冰模组功能测试"
            0x05 -> "TestCmd=IO控制测试"
            else -> "TestCmd=0x%02X".format(testCmd)
        }
        if (d1 != null) lines += "DATA1=0x%02X".format(d1)
        if (d2 != null) lines += "DATA2=0x%02X".format(d2)
        if (d3 != null) lines += "DATA3=0x%02X".format(d3)
        return lines
    }

    private fun decode1B(d: ByteArray): List<String> {
        if (d.isEmpty()) return emptyList()
        return listOf("电控锁参数=${GsBytes.hex(d)}")
    }

    private fun decode1D(d: ByteArray): List<String> {
        if (d.isEmpty()) return emptyList()
        val lines = mutableListOf("Drink_NO=${GsCommands.drinkNo(GsBytes.u8(d, 0))}")
        if (d.size > 1) lines += "流程DATA=${GsBytes.hex(d, 1)}"
        return lines
    }

    private fun decode025(d: ByteArray): List<String> {
        if (d.size < 13) return short(d)
        return listOf(
            "子命令=0x%02X".format(GsBytes.u8(d, 0)),
            "通道相关=${GsBytes.hex(d, 1)}",
        )
    }

    private fun decode026(reset: Int): List<String> {
        val flags = listOf(
            0 to "主控复位",
            1 to "前门模组复位",
            2 to "制冰模组复位",
            3 to "小料模组复位",
        )
        return formatStatusBits("RESET_INFO", reset.toLong(), null, flags)
    }

    private fun decode027(d: ByteArray): List<String> {
        if (d.size < 9) return short(d)
        return listOf(
            "清洗水温=${GsBytes.u8(d, 0)}℃",
            "现磨清洗水量=${GsBytes.u8(d, 1)}mL",
            "现磨清洗次数=${GsBytes.u8(d, 2)}",
            "后续步骤=${GsBytes.hex(d, 3)}",
        )
    }

    private fun decode029Command(d: ByteArray): List<String> {
        if (d.isEmpty()) return emptyList()
        val opt = GsBytes.u8(d, 0)
        val lines = mutableListOf(
            when (opt) {
                0x01 -> "OPT=开始排空水路"
                0x02 -> "OPT=查询排空状态"
                0x03 -> "OPT=通知是否继续执行"
                else -> "OPT=0x%02X".format(opt)
            },
        )
        if (d.size >= 3) lines += "OPTINFO=0x%04X".format(GsBytes.u16be(d, 1))
        return lines
    }

    private fun decode029Response(d: ByteArray): List<String> {
        if (d.size < 3) return short(d)
        val lines = mutableListOf("STA=${GsCommands.staOf(GsBytes.u8(d, 0))}")
        val run = GsBytes.u16be(d, 1).toLong()
        val op = GsBytes.bits(run, 0, 6)
        val opText = when (op) {
            0x01 -> "正在清空冷胆上水路"
            0x02 -> "正在清空热胆上水路"
            0x03 -> "正在清空抽水泵水路"
            0x04 -> "正在清空冷热上水路"
            0x05 -> "等待清除下水路通知"
            0x06 -> "正在清空下水路"
            0x07 -> "正在清空现磨内部水路"
            else -> null
        }
        if (opText != null) lines += opText
        if (GsBytes.bit(run, 7) == 1) lines += "等待受权=是"
        val result = GsBytes.bits(run, 14, 15)
        lines += when (result) {
            0 -> "执行结果=未执行"
            1 -> "执行结果=正在执行"
            2 -> "执行结果=成功完成"
            3 -> "执行结果=失败完成"
            else -> "执行结果=$result"
        }
        return lines
    }

    private fun decode019(d: ByteArray): List<String> {
        if (d.isEmpty()) return emptyList()
        return listOf("制冰状态DATA=${GsBytes.hex(d)}")
    }

    private fun decode1E(d: ByteArray): List<String> {
        if (d.size < 4) return short(d)
        val lines = mutableListOf<String>()
        val info1 = GsBytes.u32be(d, 0)
        val overall = when (GsBytes.bits(info1, 0, 1)) {
            0 -> "正常(空闲)"
            1 -> "正常(忙)"
            2 -> "异常"
            3 -> "故障"
            else -> "?"
        }
        lines += formatStatusBits(
            "ST_INFO1",
            info1,
            "整体状态=$overall",
            listOf(
                2 to "前门模组离线",
                3 to "制冰模组离线",
                4 to "现磨模组离线",
                5 to "前门模组故障",
                6 to "制冰模组故障",
                7 to "现磨模组故障",
                8 to "无杯",
                9 to "无盖",
                10 to "水箱缺水",
                11 to "废水箱警告",
                12 to "开机加热",
                13 to "杯托有杯",
                14 to "豆仓无豆",
                15 to "主控传感器异常",
                16 to "1#水泵无水",
                17 to "2#水泵无水",
                18 to "3#水泵无水",
                19 to "4#水泵无水",
                20 to "1#水泵故障",
                21 to "2#水泵故障",
                22 to "3#水泵故障",
                23 to "4#水泵故障",
                24 to "增压泵故障",
                25 to "热胆故障",
                26 to "冷胆故障",
                27 to "排空电磁阀故障",
                28 to "板载1#落杯器故障",
                29 to "板载2#落杯器故障",
                30 to "轨道故障",
                31 to "柜门电子锁故障",
            ),
        )
        if (d.size >= 8) {
            val info2 = GsBytes.u32be(d, 4)
            lines += formatStatusBits(
                "ST_INFO2",
                info2,
                null,
                listOf(
                    0 to "摆废盘异常",
                    1 to "集粉盘异常",
                    2 to "任一落杯器无杯",
                    3 to "任一落杯器故障",
                    4 to "萃茶模组离线",
                    5 to "萃茶模组故障",
                    6 to "扩展/小料模组离线",
                    7 to "扩展/小料模组故障",
                    8 to "苏打模组离线",
                    9 to "苏打模组故障",
                ),
            )
        }
        if (d.size > 8) {
            // remaining reserved ST_INFO3-8 and optional Drink_NO
            val rest = d.size - 8
            if (rest >= 1 && rest <= 4) {
                // might be Drink_NO only in short forms; sample has 8 bytes data (ST_INFO1+2)
            } else if (rest > 0) {
                lines += "保留/Drink=${GsBytes.hex(d, 8)}"
            }
        }
        return lines
    }

    private fun decode1F(d: ByteArray): List<String> {
        if (d.size < 4) return short(d)
        val info = GsBytes.u32be(d, 0)
        val drinkBits = GsBytes.bits(info, 0, 3)
        val drink = when (drinkBits) {
            in 1..7 -> "饮料$drinkBits"
            else -> "编号=$drinkBits"
        }
        val result = when (GsBytes.bits(info, 30, 31)) {
            0 -> "未执行制饮"
            1 -> "正在执行制饮"
            2 -> "制饮成功结束"
            3 -> "制饮失败结束"
            else -> "?"
        }
        val fail = GsBytes.bits(info, 24, 29)
        val lines = mutableListOf<String>()
        lines += formatStatusBits(
            "DK_INFO1",
            info,
            "制作=$drink $result",
            listOf(
                4 to "冷饮",
                5 to "协议命令制饮",
                6 to "等待取杯",
                7 to "已成功落杯/放杯",
                8 to "需同步操作",
                9 to "异常流程",
            ),
            multiBits = listOf(
                Triple(10, 16) { step -> if (step != 0) "当前步骤=$step" else null },
                Triple(17, 23) { total -> if (total != 0) "总步骤=$total" else null },
            ),
        )
        if (fail != 0) lines += "制作失败原因=$fail"
        if (d.size > 4) lines += "保留=${GsBytes.hex(d, 4)}"
        return lines
    }

    private fun decode022(d: ByteArray): List<String> {
        if (d.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var i = 0
        if (d.size >= 1) {
            lines += "STA=${GsCommands.staOf(GsBytes.u8(d, 0))}"
            i = 1
        }
        if (d.size >= i + 8) {
            lines += "ST_INFO=0x%08X".format(GsBytes.u32be(d, i))
            lines += "ST_INFO2=0x%08X".format(GsBytes.u32be(d, i + 4))
            i += 8
        }
        if (d.size >= i + 4) {
            lines += "OP_INFO=0x%08X".format(GsBytes.u32be(d, i))
            i += 4
        }
        if (d.size >= i + 2) {
            lines += "OBJ_NO=0x%04X".format(GsBytes.u16be(d, i))
            i += 2
        }
        if (d.size >= i + 2) {
            lines += "INFO_CODE=0x%04X".format(GsBytes.u16be(d, i))
            i += 2
        }
        if (i < d.size) lines += "AUT-INFO=${GsBytes.hex(d, i)}"
        return lines
    }

    private fun decode02A(d: ByteArray): List<String> {
        if (d.size < 7) return short(d)
        val lines = mutableListOf("STA=${GsCommands.staOf(GsBytes.u8(d, 0))}")
        val kc = GsBytes.u16be(d, 1).toLong()
        val qj = GsBytes.u32be(d, 3)
        for (i in 0 until 10) {
            if (GsBytes.bit(kc, i) == 1) lines += "${i + 1}#落杯器杯仓=有杯"
        }
        for (i in 0 until 10) {
            val st = GsBytes.bits(qj, i * 3, i * 3 + 2)
            val text = cupperState(st)
            if (text != null && st != 0) lines += "${i + 1}#落杯器=$text"
        }
        return lines
    }

    private fun cupperState(v: Int): String? = when (v) {
        0 -> "无此功能"
        1 -> "空闲"
        2 -> "忙"
        3 -> "完成操作"
        4 -> "故障"
        else -> null
    }

    private fun decode02B(d: ByteArray): List<String> {
        if (d.size < 4) return short(d)
        val lines = mutableListOf(
            "STA=${GsCommands.staOf(GsBytes.u8(d, 0))}",
            "落杯器索引=${GsBytes.u8(d, 1)}",
        )
        val info = if (d.size >= 4) GsBytes.u16be(d, 2).toLong() else 0L
        lines += formatStatusBits(
            "INFO",
            info,
            null,
            listOf(
                0 to "落杯电机工作中",
                1 to "落杯电机限位已触发",
                2 to "杯仓有杯",
                3 to "落杯电机故障",
                4 to "落杯电机限位器故障",
                5 to "杯仓有杯传感器故障",
                6 to "转盘电机工作中",
                7 to "转盘电机限位已触发",
                8 to "转盘电机故障",
                9 to "转盘电机限位器故障",
                10 to "落杯电机执行超时",
                11 to "落杯后杯座未检测到杯子",
                12 to "转盘电机运行超时",
                13 to "正在执行转盘操作",
            ),
        )
        return lines
    }

    private fun decode02C(d: ByteArray): List<String> {
        if (d.size < 1) return emptyList()
        val lines = mutableListOf("STA=${GsCommands.staOf(GsBytes.u8(d, 0))}")
        if (d.size < 17) {
            if (d.size > 1) lines += "INFO=${GsBytes.hex(d, 1)}"
            return lines
        }
        // PCBAT may be omitted in response; layout: STA + SV1..SV4(8) + HV(4) + PV(4) ...
        var i = 1
        if (d.size >= i + 8) {
            val sv1 = GsBytes.u16be(d, i)
            val sv2 = GsBytes.u16be(d, i + 2)
            val sv3 = GsBytes.u16be(d, i + 4)
            val sv4 = GsBytes.u16be(d, i + 6)
            lines += "固件版本=$sv1.$sv2.$sv3.$sv4"
            i += 8
        }
        if (d.size >= i + 4) {
            lines += "PCBA版本=${GsBytes.u8(d, i)}.${GsBytes.u8(d, i + 1)}.${GsBytes.u8(d, i + 2)}.${GsBytes.u8(d, i + 3)}"
            i += 4
        }
        if (d.size >= i + 4) {
            lines += "协议版本=${GsBytes.u8(d, i)}.${GsBytes.u8(d, i + 1)}.${GsBytes.u8(d, i + 2)}.${GsBytes.u8(d, i + 3)}"
            i += 4
        }
        if (i < d.size) lines += "OTHER=${GsBytes.hex(d, i)}"
        return lines
    }
}
