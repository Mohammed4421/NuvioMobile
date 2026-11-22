import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("framestats_input", ROOT / ".github/plus/framestats-input.py")
fs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fs)

# The header Android 14 printed in performance run 45008254583.
NEW = ("Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,AnimationStart,"
       "PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,FrameStartTime,SyncQueued,SyncStart,"
       "IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,"
       "GpuCompleted,SwapBuffersCompleted,DisplayPresentTime,CommandSubmissionCompleted,")
OLD = ("Flags,INTENDED_VSYNC,VSYNC,OLDEST_INPUT_EVENT,NEWEST_INPUT_EVENT,HANDLE_INPUT_START,ANIMATION_START,"
       "PERFORM_TRAVERSALS_START,DRAW_START,SYNC_QUEUED,SYNC_START,ISSUE_DRAW_COMMANDS_START,SWAP_BUFFERS,"
       "FRAME_COMPLETED,")


def new_row(flags, vsync, input_id, completed):
    cells = ["0"] * 23
    cells[0], cells[2], cells[4], cells[5], cells[16] = str(flags), str(vsync), str(input_id), str(vsync + 1), str(completed)
    return ",".join(cells) + ","


def old_row(flags, vsync, handle_input, completed):
    cells = ["0"] * 14
    cells[0], cells[1], cells[5], cells[13] = str(flags), str(vsync), str(handle_input), str(completed)
    return ",".join(cells) + ","


class FramestatsInputTest(unittest.TestCase):
    def test_android_12_and_later_frames_with_an_input_event(self):
        text = "\n".join(["---PROFILEDATA---", NEW,
                          new_row(0, 1_000_000_000, -387647100, 1_012_000_000),  # 12 ms, touch
                          new_row(0, 2_000_000_000, 55, 2_020_000_000),          # 20 ms, touch
                          new_row(0, 3_000_000_000, 77, 3_016_000_000),          # 16 ms, touch
                          new_row(0, 4_000_000_000, 0, 4_090_000_000),           # no touch: ignored
                          new_row(1, 5_000_000_000, 9, 5_200_000_000),           # flagged frame: ignored
                          "---PROFILEDATA---"])
        self.assertEqual(16.0, fs.input_frame_ms(text))

    def test_older_android_frames_with_handle_input_start(self):
        text = "\n".join(["---PROFILEDATA---", OLD,
                          old_row(0, 1_000_000_000, 1_001_000_000, 1_030_000_000),
                          old_row(0, 2_000_000_000, 2_001_000_000, 2_010_000_000),
                          old_row(0, 3_000_000_000, 0, 3_090_000_000),
                          "---PROFILEDATA---"])
        self.assertEqual(20.0, fs.input_frame_ms(text))

    def test_no_touch_frames_gives_nothing(self):
        text = "\n".join(["---PROFILEDATA---", NEW, new_row(0, 1_000_000_000, 0, 1_010_000_000), "---PROFILEDATA---"])
        self.assertIsNone(fs.input_frame_ms(text))
        self.assertIsNone(fs.input_frame_ms("Total frames rendered: 0"))


if __name__ == "__main__":
    unittest.main()
