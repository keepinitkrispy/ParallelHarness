import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE = Path(__file__).with_name("twosome-server.py")
spec = importlib.util.spec_from_file_location("cartographer_room", MODULE)
room = importlib.util.module_from_spec(spec)
spec.loader.exec_module(room)


class RoomModelsTest(unittest.TestCase):
    def test_selection_is_bounded_and_attributed(self):
        self.assertEqual(room.selected_models(None), ["chatgpt", "claude"])
        self.assertEqual(room.selected_models(["local"]), ["local"])
        for choice in ([], ["chatgpt"] * 2, ["unknown"], ["chatgpt"] * 4):
            with self.assertRaises(ValueError):
                room.selected_models(choice)
        with tempfile.TemporaryDirectory() as dirname:
            with patch.object(room, "STATE", Path(dirname)), patch.object(
                room, "EVENTS", Path(dirname) / "events.jsonl"
            ), patch.object(room, "CONTROL", Path(dirname) / "control.json"), patch.object(
                room, "call_model", side_effect=lambda which, events: "reply from " + which
            ):
                replies = room.run_turn("hello", "Ryan", "observed", "test",
                                        models=["chatgpt", "claude", "local"])
                self.assertEqual([item["speaker"] for item in replies],
                                 ["ChatGPT", "Claude", "Local model"])
                (Path(dirname) / "control.json").write_text(json.dumps({"mode": "paused"}))
                with self.assertRaises(ValueError):
                    room.run_turn("hello", "Ryan", "observed", "test", models=["chatgpt"])


if __name__ == "__main__":
    unittest.main()
