package com.chyi.alog.decode.gs

/**
 * 0x20 / 0x21 Modbus passthrough decode (GS_CF_PR_API_1.5F + 附1).
 */
object GsPassthrough {
    fun decode(frame: GsFrame): List<String> {
        val d = frame.data
        if (d.isEmpty()) return listOf("无 Modbus 载荷")
        return if (frame.command == 0x20) {
            if (frame.isCommand) decode20Cmd(d) else decode20Rsp(d)
        } else {
            if (frame.isCommand) decode21Cmd(d) else decode21Rsp(d)
        }
    }

    private fun decode20Cmd(d: ByteArray): List<String> {
        if (d.size < 6) return listOf("Modbus_CMD=${GsBytes.hex(d)} (长度不足)")
        val addr = GsBytes.u8(d, 0)
        val fc = GsBytes.u8(d, 1)
        val start = GsBytes.u16be(d, 2)
        val cnt = GsBytes.u16be(d, 4)
        val lines = mutableListOf(
            "模组=${GsCommands.moduleAddr(addr)}",
            "FC=0x%02X".format(fc) + if (fc == 0x03) "(读寄存器)" else "",
            "START-REG=0x%04X".format(start),
            "REG-CNT=$cnt",
        )
        namedReg(addr, start)?.let { lines += "寄存器=$it" }
        return lines
    }

    private fun decode20Rsp(d: ByteArray): List<String> {
        if (d.size < 2) return listOf("Modbus_RES=${GsBytes.hex(d)}")
        val addr = GsBytes.u8(d, 0)
        val fc = GsBytes.u8(d, 1)
        val lines = mutableListOf("模组=${GsCommands.moduleAddr(addr)}")
        when {
            fc == 0x03 && d.size >= 3 -> {
                val dlen = GsBytes.u8(d, 2)
                lines += "FC=0x03(读成功) DLEN=$dlen"
                val dataStart = 3
                val dataEnd = minOf(d.size, dataStart + dlen)
                // CRC may follow; ignore trailing 2 if present beyond DLEN
                val payload = d.copyOfRange(dataStart, dataEnd)
                lines += decodeReadPayload(addr, payload)
            }
            (fc and 0x80) != 0 && d.size >= 3 -> {
                lines += "异常FC=0x%02X".format(fc)
                lines += "ECODE=${GsCommands.modbusEcode(GsBytes.u8(d, 2))}"
            }
            else -> lines += "Modbus_RES=${GsBytes.hex(d)}"
        }
        return lines
    }

    private fun decode21Cmd(d: ByteArray): List<String> {
        if (d.size < 2) return listOf("Modbus_CMD=${GsBytes.hex(d)}")
        val addr = GsBytes.u8(d, 0)
        val fc = GsBytes.u8(d, 1)
        val lines = mutableListOf("模组=${GsCommands.moduleAddr(addr)}")
        when (fc) {
            0x06 -> if (d.size >= 6) {
                val reg = GsBytes.u16be(d, 2)
                val value = GsBytes.u16be(d, 4)
                lines += "FC=0x06(写单寄存器)"
                lines += "REG=0x%04X".format(reg) + (namedWriteReg(addr, reg)?.let { "($it)" } ?: "")
                lines += "VALUE=0x%04X ($value)".format(value)
            } else {
                lines += "Modbus_CMD=${GsBytes.hex(d)}"
            }
            0x10 -> {
                lines += "FC=0x10(写多寄存器)"
                lines += "DU=${GsBytes.hex(d, 2)}"
            }
            else -> lines += "FC=0x%02X DATA=${GsBytes.hex(d, 2)}".format(fc)
        }
        return lines
    }

    private fun decode21Rsp(d: ByteArray): List<String> {
        if (d.size < 2) return listOf("Modbus_RES=${GsBytes.hex(d)}")
        val addr = GsBytes.u8(d, 0)
        val fc = GsBytes.u8(d, 1)
        val lines = mutableListOf("模组=${GsCommands.moduleAddr(addr)}")
        when {
            fc == 0x06 || fc == 0x10 -> {
                lines += "FC=0x%02X(写成功)".format(fc)
                if (d.size >= 6) {
                    lines += "REG=0x%04X".format(GsBytes.u16be(d, 2))
                    lines += "VALUE/CNT=0x%04X".format(GsBytes.u16be(d, 4))
                } else if (d.size > 2) {
                    lines += "DU=${GsBytes.hex(d, 2)}"
                }
            }
            (fc and 0x80) != 0 && d.size >= 3 -> {
                lines += "异常FC=0x%02X".format(fc)
                lines += "ECODE=${GsCommands.modbusEcode(GsBytes.u8(d, 2))}"
            }
            else -> lines += "Modbus_RES=${GsBytes.hex(d)}"
        }
        return lines
    }

    private fun decodeReadPayload(addr: Int, payload: ByteArray): List<String> {
        if (payload.isEmpty()) return listOf("DATA空")
        val lines = mutableListOf<String>()
        when (addr) {
            0x01 -> {
                // often 2 regs -> 4 bytes as L16/H16 status (same as 0x1E ST_INFO1)
                if (payload.size >= 4) {
                    val v = GsBytes.u32FromL16H16(payload, 0)
                    lines += decodeMainStatus(v)
                    if (payload.size > 4) lines += "其余寄存器=${GsBytes.hex(payload, 4)}"
                } else {
                    lines += "寄存器数据=${GsBytes.hex(payload)}"
                }
            }
            0x02 -> if (payload.size >= 4) {
                lines += decodeFrontDoor(GsBytes.u32FromL16H16(payload, 0))
            } else {
                lines += "寄存器数据=${GsBytes.hex(payload)}"
            }
            0x03 -> if (payload.size >= 4) {
                lines += decodeIce(GsBytes.u32FromL16H16(payload, 0))
            } else {
                lines += "寄存器数据=${GsBytes.hex(payload)}"
            }
            0x04 -> lines += decodeGrinder(payload)
            else -> {
                if (payload.size >= 4 && payload.size % 2 == 0) {
                    // try L16/H16 pair dump
                    var i = 0
                    while (i + 3 < payload.size) {
                        val v = GsBytes.u32FromL16H16(payload, i)
                        lines += "INFO=0x%08X".format(v)
                        i += 4
                    }
                    if (i < payload.size) lines += "尾=${GsBytes.hex(payload, i)}"
                } else {
                    lines += "寄存器数据=${GsBytes.hex(payload)}"
                }
            }
        }
        return lines
    }

    private fun decodeMainStatus(info1: Long): List<String> {
        val overall = when (GsBytes.bits(info1, 0, 1)) {
            0 -> "正常(空闲)"
            1 -> "正常(忙)"
            2 -> "异常"
            3 -> "故障"
            else -> "?"
        }
        return formatStatusBits(
            "主控状态",
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
            ),
        )
    }

    private fun decodeFrontDoor(fmst: Long): List<String> {
        fun unit(from: Int, name: String): String? {
            val v = GsBytes.bits(fmst, from, from + 2)
            val t = when (v) {
                0 -> "无此功能"
                1 -> "空闲"
                2 -> "忙"
                3 -> "完成操作"
                4 -> "故障"
                else -> return null
            }
            return if (v == 0) null else "$name=$t"
        }
        val lines = mutableListOf("前门状态=0x%08X".format(fmst))
        listOf(
            0 to "传杯器",
            3 to "前门",
            6 to "落盖器",
            9 to "落杯器1",
            12 to "落杯器2",
            15 to "落杯红外",
            18 to "压盖器",
            21 to "称重",
            24 to "电子锁",
        ).forEach { (bit, name) -> unit(bit, name)?.let { lines += it } }
        if (GsBytes.bit(fmst, 30) == 1) lines += "运行状态=忙"
        if (GsBytes.bit(fmst, 31) == 1) lines += "模块整体=故障"
        return lines
    }

    private fun decodeIce(imst: Long): List<String> {
        val overall = when (GsBytes.bits(imst, 0, 2)) {
            0 -> "正常空闲"
            1 -> "正常制冰中"
            2 -> "异常空闲"
            3 -> "异常制冰中"
            4 -> "故障"
            else -> null
        }
        val lines = mutableListOf<String>()
        lines += formatStatusBits(
            "制冰状态",
            imst,
            overall?.let { "整体=$it" },
            listOf(
                15 to "压缩机工作超时",
                31 to "环境水温温度越界",
            ),
            multiBits = listOf(
                Triple(3, 4) { v -> faultPair("压缩机", v) },
                Triple(5, 6) { v -> faultPair("散热风扇", v) },
                Triple(7, 8) { v -> faultPair("电磁销", v) },
                Triple(9, 10) { v -> faultPair("制冰电机", v) },
                Triple(11, 12) { v -> faultPair("直流水泵", v) },
                Triple(13, 14) { v -> faultPair("直流推冰电机", v) },
            ),
        )
        return lines
    }

    private fun faultPair(name: String, v: Int): String? = when (v) {
        0 -> null
        1 -> "$name=开路故障"
        2 -> "$name=过流故障"
        else -> "$name=$v"
    }

    private fun decodeGrinder(payload: ByteArray): List<String> {
        if (payload.size < 2) return listOf("现磨DATA=${GsBytes.hex(payload)}")
        val st = GsBytes.u16be(payload, 0).toLong()
        val lines = mutableListOf<String>()
        lines += formatStatusBits(
            "现磨STINFO",
            st,
            null,
            listOf(
                0 to "现磨在线",
                1 to "现磨故障",
            ),
            multiBits = listOf(
                Triple(2, 3) { v ->
                    when (v) {
                        0 -> null
                        1 -> "工作状态=正在执行"
                        2 -> "工作状态=操作成功"
                        3 -> "工作状态=操作失败"
                        else -> null
                    }
                },
            ),
        )
        // Note: bit0=1 means online (set bit is meaningful even when "normal")
        if (payload.size >= 3) {
            val gcst1 = GsBytes.u8(payload, 2).toLong()
            lines += formatStatusBits(
                "GCST1",
                gcst1,
                null,
                listOf(
                    0 to "NTC异常",
                    1 to "核芯堵转",
                    2 to "需要排空",
                    5 to "咖啡锅炉超温",
                ),
            )
        }
        if (payload.size >= 4) {
            val gcst2 = GsBytes.u8(payload, 3).toLong()
            lines += formatStatusBits(
                "GCST2",
                gcst2,
                null,
                listOf(2 to "酿造核芯缺位"),
            )
        }
        if (payload.size > 4) lines += "其余=${GsBytes.hex(payload, 4)}"
        return lines
    }

    private fun namedReg(addr: Int, start: Int): String? = when {
        addr == 0x01 && start == 0x0000 -> "主控整体状态"
        addr == 0x01 && start == 0x0223 -> "主控版本信息"
        addr == 0x01 && start == 0x0204 -> "主控版本信息"
        addr == 0x02 && start == 0x0000 -> "前门整体状态"
        addr == 0x03 && start == 0x0000 -> "制冰整体状态"
        addr == 0x04 && start == 0x8001 -> "现磨故障信息"
        else -> null
    }

    private fun namedWriteReg(addr: Int, reg: Int): String? = when {
        addr == 0x01 && reg == 0x023E -> "热胆/冷胆功能"
        addr == 0x02 && reg == 0x0022 -> "门灯"
        addr == 0x03 && reg == 0x0019 -> "推冰"
        addr == 0x04 && reg == 0x8003 -> "现磨水温"
        else -> null
    }
}
