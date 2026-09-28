import importlib.util
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
        self.assertEqual(room.selected_models(None), ["chatgpt", "claude", "gemini"])
        self.assertEqual(room.selected_models(["local"]), ["local"])
        for choice in ([], ["chatgpt"] * 2, ["unknown"], ["chatgpt"] * 5):
            with self.assertRaises(ValueError):
                room.selected_models(choice)

    def test_each_selected_model_speaks_even_when_a_peer_fails(self):
        names = {"chatgpt": "ChatGPT", "claude": "Claude", "gemini": "Gemini"}
        with tempfile.TemporaryDirectory() as dirname:
            state = Path(dirname)
            with patch.object(room, "STATE", state), patch.object(
                room, "EVENTS", state / "events.jsonl"
            ), patch.object(room, "CONTROL", state / "control.json"), patch.object(
                room, "room_mode", return_value="running"
            ), patch.object(room, "latest_user_event_id", return_value="trigger"), patch.object(
                room, "call_model", side_effect=lambda which, events, followup=False: (
                    (_ for _ in ()).throw(RuntimeError("bridge offline"))
                    if which == "claude" else "reply from " + which
                )
            ):
                room.append_event("Ryan", "hello", source="test")
                with patch.object(room, "latest_user_event_id", return_value=room.read_events()[-1]["id"]), patch.object(
                    room, "AUTO_MAX_ROUNDS", 1
                ):
                    room.autonomous_conversation(room.read_events()[-1]["id"], tuple(names))
                events = room.read_events()

        assistant_rows = [event for event in events if event.get("source") == "assistant_bridge"]
        self.assertCountEqual([event["speaker"] for event in assistant_rows], names.values())
        error = next(event for event in assistant_rows if event["speaker"] == "Claude")
        self.assertEqual(error["status"], "error")
        self.assertEqual(error["kind"], "message")
        self.assertIn("bridge offline", error["text"])

    def test_all_models_receive_equal_standing_and_role_weighting_contract(self):
        roles = {
            "ChatGPT": "lead the room's coordination",
            "Claude": "deep coding and implementation analysis",
            "Gemini": "drive creative engineering and conceptual capability discovery",
        }
        for name, role in roles.items():
            prompt = room.prompt_for(name, [{"kind": "message", "speaker": "Ryan", "text": "go"}])
            self.assertIn("equal standing and equal opportunity", prompt)
            self.assertIn("task-specific role definitions", prompt)
            self.assertIn(role, prompt)
            self.assertNotIn("second model", prompt)


if __name__ == "__main__":
    unittest.main()
