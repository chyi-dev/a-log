import unittest

import gs_serial


class GsSerialTest(unittest.TestCase):
    def test_annotates_send_receive_and_keeps_business_lines(self):
        text = (
            "2026-08-18 00:00:00.901 Coffee-Machine:/dev/ttyS4---发送：AA 55 02 1E 1F\n"
            "2026-08-18 00:00:00.925 Coffee-Machine:/dev/ttyS4---接收：A5 5A 0A 1E 00 00 00 00 00 00 00 00 27\n"
            "2026-08-18 00:00:02.859 Coffee-Machine:播放完毕，开启下一轮播放;datas.size();1\n"
        )
        out = gs_serial.annotate_text(text)
        lines = [ln for ln in out.splitlines() if ln]
        self.assertIn("发送：AA 55 02 1E 1F", lines[0])
        self.assertTrue(lines[1].startswith("  "))
        self.assertIn("0x1E", lines[1])
        self.assertIn("查询主控运行状态", lines[1])
        self.assertIn("SUM=ok", lines[1])
        self.assertIn("接收：A5 5A", lines[2])
        self.assertTrue(lines[3].startswith("  "))
        self.assertIn("查询主控运行状态", lines[3])
        self.assertTrue(
            any(ln.endswith("播放完毕，开启下一轮播放;datas.size();1") and not ln.startswith("  ") for ln in lines)
        )

    def test_annotates_compact_received_message(self):
        text = "2026-08-18 00:00:00.926 Coffee-Machine:查询主控运行状态_收到消息：A55A0A1E000000000000000027\n"
        out = gs_serial.annotate_text(text)
        self.assertIn("查询主控运行状态", out)
        self.assertTrue(any(ln.startswith("  ") and "0x1E" in ln for ln in out.splitlines()))

    def test_marks_non_gs_frame(self):
        text = "2026-08-18 00:00:06.282 Coffee-Machine:/dev/ttyS3---接收：46 46 20 0D 0A\n"
        out = gs_serial.annotate_text(text)
        self.assertIn("非高盛帧", out)

    def test_empty_data_20_has_no_modbus_payload_note(self):
        text = "2026-08-18 00:00:01.045 Coffee-Machine:/dev/ttyS3---发送：AA 55 02 20 21\n"
        out = gs_serial.annotate_text(text)
        self.assertIn("0x20", out)
        self.assertIn("无 Modbus 载荷", out)

    def test_preserves_trailing_newline(self):
        text = "2026-08-18 00:00:00.901 Coffee-Machine:/dev/ttyS4---发送：AA 55 02 1E 1F\n"
        out = gs_serial.annotate_text(text)
        self.assertTrue(out.endswith("\n"))
        self.assertFalse(out.endswith("\n\n"))

    def test_passthrough_read_mainboard_example(self):
        text = "t Coffee-Machine:/dev/ttyS4---发送：AA 55 08 20 01 03 00 00 00 02 2D\n"
        out = gs_serial.annotate_text(text)
        self.assertIn("主控", out)
        self.assertTrue("START-REG" in out or "0x0000" in out)

    def test_0c_make_complete_fixed_message(self):
        text = "t Coffee-Machine:/dev/ttyS4---接收：A5 5A 03 0C 10 1E\n"
        out = gs_serial.annotate_text(text)
        self.assertIn("饮料制作完成", out)

    def test_annotate_notes_for_details(self):
        line = "2026-08-18 00:00:00.901 Coffee-Machine:/dev/ttyS4---发送：AA 55 02 1E 1F"
        notes = gs_serial.annotate_notes(line)
        self.assertTrue(notes)
        self.assertIn("查询主控运行状态", notes[0])
        self.assertFalse(notes[0].startswith("  "))


if __name__ == "__main__":
    unittest.main()
