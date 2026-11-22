import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("framestats_input", ROOT / ".github/plus/framestats-input.py")
fs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fs)

HEADER = ("Flags,FrameTimelineVsyncId,INTENDED_VSYNC,VSYNC,DEADLINE,OLDEST_INPUT_EVENT,NEWEST_INPUT_EVENT,"
          "HANDLE_INPUT_START,ANIMATION_START,PERFORM_TRAVERSALS_START,DRAW_START,FRAME_DEADLINE,"
          "FRAME_START_TIME,FRAME_INTERVAL,SYNC_QUEUED,SYNC_START,ISSUE_DRAW_COMMANDS_START,SWAP_BUFFERS,"
          "FRAME_COMPLETED,DEQUEUE_BUFFER_DURATION,QUEUE_BUFFER_DURATION,GPU_COMPLETED,SWAP_BUFFERS_COMPLETED,"
          "DISPLAY_PRESENT_TIME,")


def row(flags, vsync, handle_input, completed):
    cells = ["0"] * 25
    cells[0], cells[2], cells[7], cells[18] = str(flags), str(vsync), str(handle_input), str(completed)
    return ",".join(cells) + ","


class FramestatsInputTest(unittest.TestCase):
    def test_median_of_the_frames_that_handled_a_touch(self):
        text = "\n".join(["Window: x", "---PROFILEDATA---", HEADER,
                          row(0, 1_000_000_000, 1_001_000_000, 1_012_000_000),   # 12 ms, touch
                          row(0, 2_000_000_000, 2_001_000_000, 2_020_000_000),   # 20 ms, touch
                          row(0, 3_000_000_000, 3_001_000_000, 3_016_000_000),   # 16 ms, touch
                          row(0, 4_000_000_000, 0, 4_090_000_000),               # no touch: ignored
                          row(1, 5_000_000_000, 5_001_000_000, 5_200_000_000),   # flagged frame: ignored
                          "---PROFILEDATA---"])
        self.assertEqual(16.0, fs.input_frame_ms(text))

    def test_no_touch_frames_gives_nothing(self):
        text = "\n".join(["---PROFILEDATA---", HEADER, row(0, 1_000_000_000, 0, 1_010_000_000), "---PROFILEDATA---"])
        self.assertIsNone(fs.input_frame_ms(text))
        self.assertIsNone(fs.input_frame_ms("Total frames rendered: 0"))


if __name__ == "__main__":
    unittest.main()
