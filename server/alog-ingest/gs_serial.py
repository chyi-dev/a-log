# GS coffee-machine UART decode (GS_CF_PR_API_1.5F), port of alog-decode gs package.
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, List, Optional, Sequence, Tuple

_SEND_RECV = re.compile(r"(发送|接收)[:：]\s*([0-9A-Fa-f][0-9A-Fa-f\s]*)\s*$")
_RECEIVED_MSG = re.compile(r"_收到消息[:：]\s*([0-9A-Fa-f]+)\s*$")
_PURE_HEX = re.compile(r"^[0-9A-Fa-f][0-9A-Fa-f\s]*$")
_LEGACY_LINE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} [^:]+:(.*)$")

_NAMES = {
    0x01: "制作一杯指定饮品",
    0x04: "设置热饮的温度门限值",
    0x05: "设置冷饮的温度门限值",
    0x06: "读取指定饮料的销售杯数",
    0x07: "设置咖啡机出杯方式",
    0x08: "咖啡机落杯功能检测",
    0x09: "咖啡机自动全检",
    0x0A: "清洗所有速溶管道",
    0x0B: "查询咖啡机状态",
    0x0C: "查询错误代码",
    0x0E: "设置机器本地指定饮料的价格",
    0x0F: "查询咖啡机余额",
    0x10: "执行找零操作",
    0x12: "清洗指定速溶配方管道",
    0x15: "设置指定饮料的出水出料配方时间",
    0x17: "现磨咖啡机制作完成通知",
    0x19: "查询制冰机状态",
    0x1A: "单元功能测试",
    0x1B: "电控锁操作",
    0x1C: "补水操作",
    0x1D: "配置指定饮料制作流程",
    0x1E: "查询主控运行状态",
    0x1F: "制作状态查询",
    0x20: "取得指定模组指定信息(透传)",
    0x21: "设置指定模组指定信息(透传)",
    0x22: "取得指定对象最后一次异常信息",
    0x23: "强制停止任务指令",
    0x24: "送杯指令",
    0x25: "速溶单通道执行指令",
    0x26: "重启/复位指令",
    0x27: "整机制饮管路清洗指令",
    0x28: "主动上报对象异常",
    0x29: "排空水路",
    0x2A: "取得多落杯器状态信息",
    0x2B: "取得指定落杯器内部详情",
    0x2C: "取得控制板基本信息",
}

_STA = {
    0x00: "设备正常/操作成功",
    0x01: "设备忙",
    0x02: "设备故障/执行失败",
    0x03: "参数错误",
    0x04: "余额不足",
    0x05: "配置参数限制了命令功能",
    0x06: "数据/配方不存在或无效",
    0x07: "当前不满足执行条件",
    0x08: "指定的设备/部件/功能不存在",
    0x7F: "控制板主动上报",
}


def parse_hex(raw: str) -> Optional[bytes]:
    cleaned = re.sub(r"\s+", "", raw.strip())
    if not cleaned or len(cleaned) % 2 != 0:
        return None
    if any(c not in "0123456789abcdefABCDEF" for c in cleaned):
        return None
    try:
        return bytes.fromhex(cleaned)
    except ValueError:
        return None


def to_spaced(data: bytes) -> str:
    return " ".join("%02X" % b for b in data)


def u8(data: bytes, i: int) -> int:
    return data[i]


def u16be(data: bytes, i: int) -> int:
    return (data[i] << 8) | data[i + 1]


def u32be(data: bytes, i: int) -> int:
    return (data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3]


def u32_from_l16_h16(data: bytes, i: int) -> int:
    lo = u16be(data, i)
    hi = u16be(data, i + 2)
    return (hi << 16) | lo


def hex_bytes(data: bytes, start: int = 0, end: Optional[int] = None) -> str:
    if end is None:
        end = len(data)
    return to_spaced(data[start:end])


def bit(value: int, b: int) -> int:
    return (value >> b) & 1


def bits(value: int, frm: int, to_inclusive: int) -> int:
    width = to_inclusive - frm + 1
    return (value >> frm) & ((1 << width) - 1)


def format_status_bits(
    label: str,
    value: int,
    summary: Optional[str],
    flags: Sequence[Tuple[int, str]],
    multi_bits: Sequence[Tuple[int, int, Callable[[int], Optional[str]]]] = (),
) -> List[str]:
    head = "%s=0x%08X" % (label, value & 0xFFFFFFFF)
    if summary:
        head += " " + summary
    lines = [head]
    for b, name in flags:
        if bit(value, b) == 1:
            lines.append("%s=是" % name)
    for frm, to, mapper in multi_bits:
        text = mapper(bits(value, frm, to))
        if text:
            lines.append(text)
    return lines


def name_of(command: int) -> str:
    return _NAMES.get(command, "未知指令")


def sta_of(code: int) -> str:
    return _STA.get(code, "未知STA(0x%02X)" % code)


def drink_no(code: int) -> str:
    if 0x01 <= code <= 0x07:
        return "热饮%d" % code
    if 0x11 <= code <= 0x17:
        return "冷饮%d" % (code - 0x10)
    return "Drink_NO=0x%02X" % code


def module_addr(addr: int) -> str:
    return {
        0x01: "主控",
        0x02: "前门模组",
        0x03: "制冰模组",
        0x04: "现磨模组",
        0x05: "萃茶模组",
        0x06: "苏打模组",
        0x80: "小料模组",
    }.get(addr, "ADDR=0x%02X" % addr)


def modbus_ecode(code: int) -> str:
    return {
        0x01: "非法功能码",
        0x02: "无效寄存器地址",
        0x03: "无效数据值",
        0x04: "设备故障",
        0x05: "请求已接受但需长时间处理",
        0x07: "设备正忙",
        0xFC: "指令透传/发送失败",
        0xFD: "模组离线或超时未应答",
        0xFE: "指定模组对象不存在",
    }.get(code, "ECODE=0x%02X" % code)


@dataclass
class Frame:
    is_command: bool
    length: int
    command: int
    data: bytes
    sum: int
    expected_sum: int
    checksum_ok: bool
    raw: bytes


def scan_frames(data: bytes) -> List[Frame]:
    frames: List[Frame] = []
    i = 0
    n = len(data)
    while i + 4 <= n:
        b0, b1 = data[i], data[i + 1]
        is_cmd = b0 == 0xAA and b1 == 0x55
        is_rsp = b0 == 0xA5 and b1 == 0x5A
        if not is_cmd and not is_rsp:
            i += 1
            continue
        length = data[i + 2]
        if length < 2:
            i += 1
            continue
        frame_end = i + 3 + length
        if frame_end > n:
            break
        command = data[i + 3]
        data_len = length - 2
        payload = data[i + 4:i + 4 + data_len] if data_len > 0 else b""
        checksum = data[frame_end - 1]
        acc = (b0 + b1 + length + command + sum(payload)) & 0xFF
        frames.append(
            Frame(
                is_command=is_cmd,
                length=length,
                command=command,
                data=payload,
                sum=checksum,
                expected_sum=acc,
                checksum_ok=checksum == acc,
                raw=data[i:frame_end],
            )
        )
        i = frame_end
    return frames


def _short(d: bytes) -> List[str]:
    if not d:
        return ["DATA长度不足"]
    return ["DATA=%s (长度不足)" % hex_bytes(d)]


def _sta_only(d: bytes) -> List[str]:
    if not d:
        return []
    return ["STA=%s" % sta_of(d[0])]


def _decode_0c(d: bytes) -> List[str]:
    if not d:
        return []
    code = d[0]
    fixed = {0x05: "落杯成功", 0x10: "饮料制作完成", 0x06: "落冰完成", 0x20: "轨道异物/压盖失败/前门模组离线"}.get(code)
    if fixed:
        return ["制饮主动上报: %s (ErrorCode=0x%02X)" % (fixed, code)]
    return format_status_bits(
        "ErrorCode",
        code,
        None,
        [(0, "缺水"), (1, "缺杯"), (2, "缺水且缺杯"), (3, "传感器故障"), (5, "轨道故障"), (6, "无盖"), (7, "设备通电首次加热")],
    )


def _decode_1e(d: bytes) -> List[str]:
    if len(d) < 4:
        return _short(d)
    info1 = u32be(d, 0)
    overall = {0: "正常(空闲)", 1: "正常(忙)", 2: "异常", 3: "故障"}.get(bits(info1, 0, 1), "?")
    lines = format_status_bits(
        "ST_INFO1",
        info1,
        "整体状态=%s" % overall,
        [
            (2, "前门模组离线"), (3, "制冰模组离线"), (4, "现磨模组离线"),
            (5, "前门模组故障"), (6, "制冰模组故障"), (7, "现磨模组故障"),
            (8, "无杯"), (9, "无盖"), (10, "水箱缺水"), (11, "废水箱警告"),
            (12, "开机加热"), (13, "杯托有杯"), (14, "豆仓无豆"), (15, "主控传感器异常"),
            (16, "1#水泵无水"), (17, "2#水泵无水"), (18, "3#水泵无水"), (19, "4#水泵无水"),
            (20, "1#水泵故障"), (21, "2#水泵故障"), (22, "3#水泵故障"), (23, "4#水泵故障"),
            (24, "增压泵故障"), (25, "热胆故障"), (26, "冷胆故障"), (27, "排空电磁阀故障"),
            (28, "板载1#落杯器故障"), (29, "板载2#落杯器故障"), (30, "轨道故障"), (31, "柜门电子锁故障"),
        ],
    )
    if len(d) >= 8:
        info2 = u32be(d, 4)
        lines.extend(
            format_status_bits(
                "ST_INFO2",
                info2,
                None,
                [
                    (0, "摆废盘异常"), (1, "集粉盘异常"), (2, "任一落杯器无杯"), (3, "任一落杯器故障"),
                    (4, "萃茶模组离线"), (5, "萃茶模组故障"), (6, "扩展/小料模组离线"),
                    (7, "扩展/小料模组故障"), (8, "苏打模组离线"), (9, "苏打模组故障"),
                ],
            )
        )
    if len(d) > 8 and (len(d) - 8) > 4:
        lines.append("保留/Drink=%s" % hex_bytes(d, 8))
    return lines


def _decode_1f(d: bytes) -> List[str]:
    if len(d) < 4:
        return _short(d)
    info = u32be(d, 0)
    drink_bits = bits(info, 0, 3)
    drink = "饮料%d" % drink_bits if 1 <= drink_bits <= 7 else "编号=%d" % drink_bits
    result = {0: "未执行制饮", 1: "正在执行制饮", 2: "制饮成功结束", 3: "制饮失败结束"}.get(bits(info, 30, 31), "?")
    fail = bits(info, 24, 29)
    lines = format_status_bits(
        "DK_INFO1",
        info,
        "制作=%s %s" % (drink, result),
        [
            (4, "冷饮"), (5, "协议命令制饮"), (6, "等待取杯"), (7, "已成功落杯/放杯"),
            (8, "需同步操作"), (9, "异常流程"),
        ],
        [
            (10, 16, lambda step: ("当前步骤=%d" % step) if step else None),
            (17, 23, lambda total: ("总步骤=%d" % total) if total else None),
        ],
    )
    if fail:
        lines.append("制作失败原因=%d" % fail)
    if len(d) > 4:
        lines.append("保留=%s" % hex_bytes(d, 4))
    return lines


def _decode_command(cmd: int, d: bytes) -> List[str]:
    if cmd == 0x01:
        if len(d) < 2:
            return _short(d)
        loc = {0x01: "本机余额消费", 0x02: "命令直接制饮"}.get(d[1], "0x%02X" % d[1])
        return ["Drink_NO=%s" % drink_no(d[0]), "LocalOrCmd=%s" % loc]
    if cmd in (0x04, 0x05):
        if len(d) < 2:
            return _short(d)
        kind = "热饮" if cmd == 0x04 else "冷饮"
        return ["%s上限=%d℃" % (kind, d[0]), "%s下限=%d℃" % (kind, d[1])]
    if cmd in (0x06, 0x12):
        return ["Drink_NO=%s" % drink_no(d[0])] if d else []
    if cmd == 0x07:
        if not d:
            return []
        cup = {0: "自动落杯", 1: "手动放杯"}.get(d[0], "0x%02X" % d[0])
        return ["CUP=%s" % cup]
    if cmd in (0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0F, 0x1E, 0x1F, 0x2A):
        return []
    if cmd == 0x0E:
        if len(d) < 2:
            return _short(d)
        return ["Drink_NO=%s" % drink_no(d[0]), "价格=%d" % d[1]]
    if cmd == 0x10:
        return ["找零参数=0x%02X" % d[0]] if d else []
    if cmd == 0x15:
        if len(d) < 3:
            return _short(d)
        return ["Drink_NO=%s" % drink_no(d[0]), "配方DATA=%s" % hex_bytes(d, 1)]
    if cmd == 0x17:
        return ["通知参数=%s" % hex_bytes(d)] if d else []
    if cmd == 0x1A:
        if not d:
            return []
        names = {0x01: "出料功能测试", 0x02: "坐标工位测试", 0x03: "前门模组功能测试", 0x04: "制冰模组功能测试", 0x05: "IO控制测试"}
        lines = ["TestCmd=%s" % names.get(d[0], "0x%02X" % d[0])]
        if len(d) > 1:
            lines.append("DATA1=0x%02X" % d[1])
        if len(d) > 2:
            lines.append("DATA2=0x%02X" % d[2])
        if len(d) > 3:
            lines.append("DATA3=0x%02X" % d[3])
        return lines
    if cmd == 0x1B:
        return ["电控锁参数=%s" % hex_bytes(d)] if d else []
    if cmd == 0x1C:
        return ["补水参数=%s" % hex_bytes(d)] if d else []
    if cmd == 0x1D:
        if not d:
            return []
        lines = ["Drink_NO=%s" % drink_no(d[0])]
        if len(d) > 1:
            lines.append("流程DATA=%s" % hex_bytes(d, 1))
        return lines
    if cmd == 0x22:
        if len(d) < 3:
            return _short(d)
        return ["保留=0x%02X" % d[0], "OBJ_NO=0x%04X" % u16be(d, 1)]
    if cmd == 0x23:
        if not d:
            return []
        target = {0x01: "强制停止制饮流程", 0x24: "强制停止送杯流程"}.get(d[0], "0x%02X" % d[0])
        return ["停止目标=%s" % target]
    if cmd == 0x24:
        if len(d) < 2:
            return _short(d)
        return ["保留=0x%02X" % d[0], "等待取杯=%dS" % d[1]]
    if cmd == 0x25:
        if len(d) < 13:
            return _short(d)
        return ["子命令=0x%02X" % d[0], "通道相关=%s" % hex_bytes(d, 1)]
    if cmd == 0x26:
        if len(d) < 2:
            return _short(d)
        return format_status_bits(
            "RESET_INFO",
            u16be(d, 0),
            None,
            [(0, "主控复位"), (1, "前门模组复位"), (2, "制冰模组复位"), (3, "小料模组复位")],
        )
    if cmd == 0x27:
        if len(d) < 9:
            return _short(d)
        return [
            "清洗水温=%d℃" % d[0],
            "现磨清洗水量=%dmL" % d[1],
            "现磨清洗次数=%d" % d[2],
            "后续步骤=%s" % hex_bytes(d, 3),
        ]
    if cmd == 0x29:
        if not d:
            return []
        opt = {0x01: "OPT=开始排空水路", 0x02: "OPT=查询排空状态", 0x03: "OPT=通知是否继续执行"}.get(d[0], "OPT=0x%02X" % d[0])
        lines = [opt]
        if len(d) >= 3:
            lines.append("OPTINFO=0x%04X" % u16be(d, 1))
        return lines
    if cmd == 0x2B:
        return ["落杯器索引=%d" % d[0]] if d else []
    if cmd == 0x2C:
        if not d:
            return []
        pcbat = "主控速溶板信息" if d[0] == 1 else "0x%02X" % d[0]
        return ["PCBAT=%s" % pcbat]
    return ["DATA=%s" % hex_bytes(d)] if d else []


def _decode_response(cmd: int, d: bytes) -> List[str]:
    if cmd in (0x01, 0x04, 0x05, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0E, 0x10, 0x12, 0x15, 0x17, 0x1A, 0x1B, 0x1C, 0x1D, 0x23, 0x24, 0x25, 0x26, 0x27):
        return _sta_only(d)
    if cmd == 0x06:
        if len(d) < 9:
            return _short(d)
        return [
            "Drink_NO=%s" % drink_no(d[0]),
            "本地销售杯数=%d" % u32be(d, 1),
            "指令制作杯数=%d" % u32be(d, 5),
        ]
    if cmd == 0x0C:
        return _decode_0c(d)
    if cmd == 0x0F:
        return ["余额=%d" % d[0]] if d else []
    if cmd == 0x19:
        return ["制冰状态DATA=%s" % hex_bytes(d)] if d else []
    if cmd == 0x1E:
        return _decode_1e(d)
    if cmd == 0x1F:
        return _decode_1f(d)
    if cmd in (0x22, 0x28):
        return _decode_022(d)
    if cmd == 0x29:
        return _decode_029_rsp(d)
    if cmd == 0x2A:
        return _decode_02a(d)
    if cmd == 0x2B:
        return _decode_02b(d)
    if cmd == 0x2C:
        return _decode_02c(d)
    return ["DATA=%s" % hex_bytes(d)] if d else []


def _decode_022(d: bytes) -> List[str]:
    if not d:
        return []
    lines = ["STA=%s" % sta_of(d[0])]
    i = 1
    if len(d) >= i + 8:
        lines.append("ST_INFO=0x%08X" % u32be(d, i))
        lines.append("ST_INFO2=0x%08X" % u32be(d, i + 4))
        i += 8
    if len(d) >= i + 4:
        lines.append("OP_INFO=0x%08X" % u32be(d, i))
        i += 4
    if len(d) >= i + 2:
        lines.append("OBJ_NO=0x%04X" % u16be(d, i))
        i += 2
    if len(d) >= i + 2:
        lines.append("INFO_CODE=0x%04X" % u16be(d, i))
        i += 2
    if i < len(d):
        lines.append("AUT-INFO=%s" % hex_bytes(d, i))
    return lines


def _decode_029_rsp(d: bytes) -> List[str]:
    if len(d) < 3:
        return _short(d)
    lines = ["STA=%s" % sta_of(d[0])]
    run = u16be(d, 1)
    op = bits(run, 0, 6)
    op_text = {
        0x01: "正在清空冷胆上水路",
        0x02: "正在清空热胆上水路",
        0x03: "正在清空抽水泵水路",
        0x04: "正在清空冷热上水路",
        0x05: "等待清除下水路通知",
        0x06: "正在清空下水路",
        0x07: "正在清空现磨内部水路",
    }.get(op)
    if op_text:
        lines.append(op_text)
    if bit(run, 7) == 1:
        lines.append("等待受权=是")
    result = bits(run, 14, 15)
    lines.append({0: "执行结果=未执行", 1: "执行结果=正在执行", 2: "执行结果=成功完成", 3: "执行结果=失败完成"}.get(result, "执行结果=%d" % result))
    return lines


def _cupper_state(v: int) -> Optional[str]:
    return {0: "无此功能", 1: "空闲", 2: "忙", 3: "完成操作", 4: "故障"}.get(v)


def _decode_02a(d: bytes) -> List[str]:
    if len(d) < 7:
        return _short(d)
    lines = ["STA=%s" % sta_of(d[0])]
    kc = u16be(d, 1)
    qj = u32be(d, 3)
    for i in range(10):
        if bit(kc, i) == 1:
            lines.append("%d#落杯器杯仓=有杯" % (i + 1))
    for i in range(10):
        st = bits(qj, i * 3, i * 3 + 2)
        text = _cupper_state(st)
        if text and st != 0:
            lines.append("%d#落杯器=%s" % (i + 1, text))
    return lines


def _decode_02b(d: bytes) -> List[str]:
    if len(d) < 4:
        return _short(d)
    lines = ["STA=%s" % sta_of(d[0]), "落杯器索引=%d" % d[1]]
    info = u16be(d, 2)
    lines.extend(
        format_status_bits(
            "INFO",
            info,
            None,
            [
                (0, "落杯电机工作中"), (1, "落杯电机限位已触发"), (2, "杯仓有杯"),
                (3, "落杯电机故障"), (4, "落杯电机限位器故障"), (5, "杯仓有杯传感器故障"),
                (6, "转盘电机工作中"), (7, "转盘电机限位已触发"), (8, "转盘电机故障"),
                (9, "转盘电机限位器故障"), (10, "落杯电机执行超时"), (11, "落杯后杯座未检测到杯子"),
                (12, "转盘电机运行超时"), (13, "正在执行转盘操作"),
            ],
        )
    )
    return lines


def _decode_02c(d: bytes) -> List[str]:
    if not d:
        return []
    lines = ["STA=%s" % sta_of(d[0])]
    if len(d) < 17:
        if len(d) > 1:
            lines.append("INFO=%s" % hex_bytes(d, 1))
        return lines
    i = 1
    lines.append("固件版本=%d.%d.%d.%d" % (u16be(d, i), u16be(d, i + 2), u16be(d, i + 4), u16be(d, i + 6)))
    i += 8
    lines.append("PCBA版本=%d.%d.%d.%d" % (d[i], d[i + 1], d[i + 2], d[i + 3]))
    i += 4
    lines.append("协议版本=%d.%d.%d.%d" % (d[i], d[i + 1], d[i + 2], d[i + 3]))
    i += 4
    if i < len(d):
        lines.append("OTHER=%s" % hex_bytes(d, i))
    return lines


def _named_reg(addr: int, start: int) -> Optional[str]:
    if addr == 0x01 and start == 0x0000:
        return "主控整体状态"
    if addr == 0x01 and start in (0x0223, 0x0204):
        return "主控版本信息"
    if addr == 0x02 and start == 0x0000:
        return "前门整体状态"
    if addr == 0x03 and start == 0x0000:
        return "制冰整体状态"
    if addr == 0x04 and start == 0x8001:
        return "现磨故障信息"
    return None


def _named_write_reg(addr: int, reg: int) -> Optional[str]:
    mapping = {
        (0x01, 0x023E): "热胆/冷胆功能",
        (0x02, 0x0022): "门灯",
        (0x03, 0x0019): "推冰",
        (0x04, 0x8003): "现磨水温",
    }
    return mapping.get((addr, reg))


def _decode_main_status(info1: int) -> List[str]:
    overall = {0: "正常(空闲)", 1: "正常(忙)", 2: "异常", 3: "故障"}.get(bits(info1, 0, 1), "?")
    return format_status_bits(
        "主控状态",
        info1,
        "整体状态=%s" % overall,
        [
            (2, "前门模组离线"), (3, "制冰模组离线"), (4, "现磨模组离线"),
            (5, "前门模组故障"), (6, "制冰模组故障"), (7, "现磨模组故障"),
            (8, "无杯"), (9, "无盖"), (10, "水箱缺水"), (11, "废水箱警告"),
            (12, "开机加热"), (13, "杯托有杯"), (14, "豆仓无豆"), (15, "主控传感器异常"),
        ],
    )


def _decode_front_door(fmst: int) -> List[str]:
    def unit(frm: int, name: str) -> Optional[str]:
        v = bits(fmst, frm, frm + 2)
        t = {0: "无此功能", 1: "空闲", 2: "忙", 3: "完成操作", 4: "故障"}.get(v)
        if t is None or v == 0:
            return None
        return "%s=%s" % (name, t)

    lines = ["前门状态=0x%08X" % (fmst & 0xFFFFFFFF)]
    for frm, name in ((0, "传杯器"), (3, "前门"), (6, "落盖器"), (9, "落杯器1"), (12, "落杯器2"), (15, "落杯红外"), (18, "压盖器"), (21, "称重"), (24, "电子锁")):
        text = unit(frm, name)
        if text:
            lines.append(text)
    if bit(fmst, 30) == 1:
        lines.append("运行状态=忙")
    if bit(fmst, 31) == 1:
        lines.append("模块整体=故障")
    return lines


def _fault_pair(name: str, v: int) -> Optional[str]:
    if v == 0:
        return None
    if v == 1:
        return "%s=开路故障" % name
    if v == 2:
        return "%s=过流故障" % name
    return "%s=%d" % (name, v)


def _decode_ice(imst: int) -> List[str]:
    overall = {0: "正常空闲", 1: "正常制冰中", 2: "异常空闲", 3: "异常制冰中", 4: "故障"}.get(bits(imst, 0, 2))
    return format_status_bits(
        "制冰状态",
        imst,
        ("整体=%s" % overall) if overall else None,
        [(15, "压缩机工作超时"), (31, "环境水温温度越界")],
        [
            (3, 4, lambda v: _fault_pair("压缩机", v)),
            (5, 6, lambda v: _fault_pair("散热风扇", v)),
            (7, 8, lambda v: _fault_pair("电磁销", v)),
            (9, 10, lambda v: _fault_pair("制冰电机", v)),
            (11, 12, lambda v: _fault_pair("直流水泵", v)),
            (13, 14, lambda v: _fault_pair("直流推冰电机", v)),
        ],
    )


def _decode_grinder(payload: bytes) -> List[str]:
    if len(payload) < 2:
        return ["现磨DATA=%s" % hex_bytes(payload)]
    st = u16be(payload, 0)
    lines = format_status_bits(
        "现磨STINFO",
        st,
        None,
        [(0, "现磨在线"), (1, "现磨故障")],
        [
            (
                2,
                3,
                lambda v: {1: "工作状态=正在执行", 2: "工作状态=操作成功", 3: "工作状态=操作失败"}.get(v),
            )
        ],
    )
    if len(payload) >= 3:
        lines.extend(
            format_status_bits(
                "GCST1",
                payload[2],
                None,
                [(0, "NTC异常"), (1, "核芯堵转"), (2, "需要排空"), (5, "咖啡锅炉超温")],
            )
        )
    if len(payload) >= 4:
        lines.extend(format_status_bits("GCST2", payload[3], None, [(2, "酿造核芯缺位")]))
    if len(payload) > 4:
        lines.append("其余=%s" % hex_bytes(payload, 4))
    return lines


def _decode_read_payload(addr: int, payload: bytes) -> List[str]:
    if not payload:
        return ["DATA空"]
    if addr == 0x01:
        if len(payload) >= 4:
            lines = _decode_main_status(u32_from_l16_h16(payload, 0))
            if len(payload) > 4:
                lines.append("其余寄存器=%s" % hex_bytes(payload, 4))
            return lines
        return ["寄存器数据=%s" % hex_bytes(payload)]
    if addr == 0x02:
        if len(payload) >= 4:
            return _decode_front_door(u32_from_l16_h16(payload, 0))
        return ["寄存器数据=%s" % hex_bytes(payload)]
    if addr == 0x03:
        if len(payload) >= 4:
            return _decode_ice(u32_from_l16_h16(payload, 0))
        return ["寄存器数据=%s" % hex_bytes(payload)]
    if addr == 0x04:
        return _decode_grinder(payload)
    lines: List[str] = []
    if len(payload) >= 4 and len(payload) % 2 == 0:
        i = 0
        while i + 3 < len(payload):
            lines.append("INFO=0x%08X" % (u32_from_l16_h16(payload, i) & 0xFFFFFFFF))
            i += 4
        if i < len(payload):
            lines.append("尾=%s" % hex_bytes(payload, i))
        return lines
    return ["寄存器数据=%s" % hex_bytes(payload)]


def _decode_passthrough(frame: Frame) -> List[str]:
    d = frame.data
    if not d:
        return ["无 Modbus 载荷"]
    if frame.command == 0x20:
        return _decode_20_cmd(d) if frame.is_command else _decode_20_rsp(d)
    return _decode_21_cmd(d) if frame.is_command else _decode_21_rsp(d)


def _decode_20_cmd(d: bytes) -> List[str]:
    if len(d) < 6:
        return ["Modbus_CMD=%s (长度不足)" % hex_bytes(d)]
    addr, fc, start, cnt = d[0], d[1], u16be(d, 2), u16be(d, 4)
    lines = [
        "模组=%s" % module_addr(addr),
        "FC=0x%02X%s" % (fc, "(读寄存器)" if fc == 0x03 else ""),
        "START-REG=0x%04X" % start,
        "REG-CNT=%d" % cnt,
    ]
    named = _named_reg(addr, start)
    if named:
        lines.append("寄存器=%s" % named)
    return lines


def _decode_20_rsp(d: bytes) -> List[str]:
    if len(d) < 2:
        return ["Modbus_RES=%s" % hex_bytes(d)]
    addr, fc = d[0], d[1]
    lines = ["模组=%s" % module_addr(addr)]
    if fc == 0x03 and len(d) >= 3:
        dlen = d[2]
        lines.append("FC=0x03(读成功) DLEN=%d" % dlen)
        payload = d[3:3 + dlen]
        lines.extend(_decode_read_payload(addr, payload))
    elif (fc & 0x80) and len(d) >= 3:
        lines.append("异常FC=0x%02X" % fc)
        lines.append("ECODE=%s" % modbus_ecode(d[2]))
    else:
        lines.append("Modbus_RES=%s" % hex_bytes(d))
    return lines


def _decode_21_cmd(d: bytes) -> List[str]:
    if len(d) < 2:
        return ["Modbus_CMD=%s" % hex_bytes(d)]
    addr, fc = d[0], d[1]
    lines = ["模组=%s" % module_addr(addr)]
    if fc == 0x06:
        if len(d) >= 6:
            reg, value = u16be(d, 2), u16be(d, 4)
            named = _named_write_reg(addr, reg)
            lines.append("FC=0x06(写单寄存器)")
            lines.append("REG=0x%04X%s" % (reg, ("(%s)" % named) if named else ""))
            lines.append("VALUE=0x%04X (%d)" % (value, value))
        else:
            lines.append("Modbus_CMD=%s" % hex_bytes(d))
    elif fc == 0x10:
        lines.append("FC=0x10(写多寄存器)")
        lines.append("DU=%s" % hex_bytes(d, 2))
    else:
        lines.append("FC=0x%02X DATA=%s" % (fc, hex_bytes(d, 2)))
    return lines


def _decode_21_rsp(d: bytes) -> List[str]:
    if len(d) < 2:
        return ["Modbus_RES=%s" % hex_bytes(d)]
    addr, fc = d[0], d[1]
    lines = ["模组=%s" % module_addr(addr)]
    if fc in (0x06, 0x10):
        lines.append("FC=0x%02X(写成功)" % fc)
        if len(d) >= 6:
            lines.append("REG=0x%04X" % u16be(d, 2))
            lines.append("VALUE/CNT=0x%04X" % u16be(d, 4))
        elif len(d) > 2:
            lines.append("DU=%s" % hex_bytes(d, 2))
    elif (fc & 0x80) and len(d) >= 3:
        lines.append("异常FC=0x%02X" % fc)
        lines.append("ECODE=%s" % modbus_ecode(d[2]))
    else:
        lines.append("Modbus_RES=%s" % hex_bytes(d))
    return lines


def decode_frame(frame: Frame) -> List[str]:
    direction = "发送" if frame.is_command else "接收"
    sum_text = "SUM=ok" if frame.checksum_ok else "SUM=不符(期望%02X 实际%02X)" % (frame.expected_sum, frame.sum)
    header = "[%s] 0x%02X %s  LEN=%d %s" % (direction, frame.command, name_of(frame.command), frame.length, sum_text)
    if frame.command in (0x20, 0x21):
        body = _decode_passthrough(frame)
    elif frame.is_command:
        body = _decode_command(frame.command, frame.data)
    else:
        body = _decode_response(frame.command, frame.data)
    return [header] + body


def _extract_msg(line: str) -> Optional[str]:
    m = _LEGACY_LINE.match(line)
    if m:
        return m.group(1)
    colon = line.find(":")
    if 0 <= colon < len(line) - 1:
        return line[colon + 1:]
    return None


def _extract_hex(msg: str) -> Optional[Tuple[Optional[str], str]]:
    m = _SEND_RECV.search(msg)
    if m:
        return m.group(1), m.group(2).strip()
    m = _RECEIVED_MSG.search(msg)
    if m:
        return "接收", m.group(1).strip()
    trimmed = msg.strip()
    if _PURE_HEX.match(trimmed) and ("AA" in trimmed.upper() or "A5" in trimmed.upper()):
        return None, trimmed
    return None


def annotate_notes(line: str) -> List[str]:
    """Decode notes for one legacy line (no leading indent, no original line)."""
    msg = _extract_msg(line)
    if msg is None:
        return []
    extracted = _extract_hex(msg)
    if extracted is None:
        return []
    direction_hint, hex_raw = extracted
    raw = parse_hex(hex_raw)
    if raw is None:
        return []
    frames = scan_frames(raw)
    if not frames:
        ascii_s = "".join(chr(b) if 0x20 <= b <= 0x7E else "." for b in raw)
        return ["非高盛帧 ASCII=%s HEX=%s" % (ascii_s, to_spaced(raw))]
    result: List[str] = []
    for frame in frames:
        decoded = decode_frame(frame)
        if direction_hint and decoded:
            rest = re.sub(r"^\[(发送|接收)]", "[%s]" % direction_hint, decoded[0])
            result.append(rest)
            result.extend(decoded[1:])
        else:
            result.extend(decoded)
    return result


def annotate_lines(lines: Sequence[str]) -> List[str]:
    if not lines:
        return []
    out: List[str] = []
    for line in lines:
        out.append(line)
        for note in annotate_notes(line):
            out.append("  " + note)
    return out


def annotate_text(legacy_txt: str) -> str:
    if not legacy_txt:
        return legacy_txt
    ends_with_nl = legacy_txt.endswith("\n")
    parts = legacy_txt.split("\n")
    if ends_with_nl and parts and parts[-1] == "":
        parts = parts[:-1]
    annotated = annotate_lines(parts)
    if not annotated:
        return "\n" if ends_with_nl else ""
    return "\n".join(annotated) + "\n"
