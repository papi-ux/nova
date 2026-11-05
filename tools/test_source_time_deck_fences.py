"""Production wiring guards; NOVA_SOURCE_TIME_SOURCE_ROOT permits retained baseline controls."""
import os
from pathlib import Path
import unittest
ROOT = Path(os.environ.get("NOVA_SOURCE_TIME_SOURCE_ROOT", Path(__file__).resolve().parents[1]))
class DeckSourceTimeFenceSourceTest(unittest.TestCase):
    def test_epoch_fence_reaches_mapper_mailbox_and_vulkan_submit_present(self):
        window = (ROOT / "clients/deck/src/stream/deck_vulkan_video_window.cpp").read_text()
        mailbox = window[window.index("bool DeckVulkanVideoWindow::submitFrame"):window.index("void DeckVulkanVideoWindow::clearFrame")]
        self.assertIn("timing.withCurrentSubmission([&]", mailbox)
        render = window[window.index("bool DeckVulkanVideoWindow::Impl::render"):window.index("void DeckVulkanVideoWindow::exposeEvent")]
        guard = render.index("job.timing.withCurrentSubmission([&]")
        end = render.index("    });", guard)
        for call in ("pl_swapchain_start_frame", "pl_swapchain_submit_frame", "pl_swapchain_swap_buffers"):
            self.assertLess(guard, render.index(call)); self.assertLess(render.index(call), end)
        mapper = (ROOT / "clients/deck/src/runtime/deck_source_clock_mapper.cpp").read_text()
        self.assertIn("submissionFence_->updateEpoch", mapper)
        session = (ROOT / "clients/deck/src/runtime/deck_native_session.cpp").read_text()
        self.assertIn("setSourceSubmissionFence(shared->sourceSubmissionFence)", session)
if __name__ == "__main__":
    unittest.main()
