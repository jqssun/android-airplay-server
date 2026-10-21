"""Guard against attributing another app's hardware layer to the receiver."""
import contextlib
import io
import unittest

from check_render_path import check


PACKAGE = "io.github.jqssun.airplay.hq"
LAYER = f"SurfaceView - {PACKAGE}/io.github.jqssun.airplay.MainActivity#0"
BUFFER = f"""+ BufferLayer ({LAYER})
  Region VisibleRegion (count=1)
    [712, 0, 1209, 1080]
      activeBuffer=[994x2160:1024,YUV]
"""


def table_entry(name=LAYER, composition="Device", frame="712 0 1209 1080", crop="0.0 0.0 994.0 2160.0"):
    return f" {name}\n  rel -2 | {composition} | {frame} | {crop}\n"


class RenderPathCheckTest(unittest.TestCase):
    def run_check(self, entries, buffer=BUFFER):
        with contextlib.redirect_stdout(io.StringIO()):
            check(buffer + "HWC layers:\n" + entries + "h/w composer state:\n", (994, 2160), PACKAGE)

    def test_other_device_layer_does_not_hide_target_client_composition(self):
        entries = table_entry("SurfaceView - unrelated/player#0") + table_entry(composition="Client")
        with self.assertRaisesRegex(ValueError, "not established"):
            self.run_check(entries)

    def test_truncated_name_matches_target(self):
        self.run_check(table_entry("SurfaceView - io.github.jqssun.airpl[...]github.jqssun.airplay.MainActivity#0"))

    def test_ambiguous_truncated_entries_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "uniquely associate"):
            self.run_check(table_entry() * 2)

    def test_wrong_frame_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "uniquely associate"):
            self.run_check(table_entry(frame="0 0 1920 1080"))

    def test_reduced_buffer_fails(self):
        with self.assertRaisesRegex(ValueError, "reduced"):
            self.run_check(table_entry(), BUFFER.replace("994x2160", "497x1080"))

    def test_reduced_crop_fails(self):
        with self.assertRaisesRegex(ValueError, "crop"):
            self.run_check(table_entry(crop="0.0 0.0 497.0 1080.0"))


if __name__ == "__main__":
    unittest.main()
