package dev.bcrick.secondo.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
	name = "mixer",
	description = "Mixer panel controls (sections, zoom, state).",
	mixinStandardHelpOptions = true,
	subcommands = {
		MixerCommand.GetState.class,
		MixerCommand.SetSection.class,
		MixerCommand.ZoomIn.class,
		MixerCommand.ZoomOut.class
	}
)
class MixerCommand {
	@ParentCommand SecondoCli parent;

	@Command(name = "get-state", description = "Get the current mixer state.")
	static class GetState implements Runnable {
		@ParentCommand private MixerCommand mixer;
		@Override
		public void run() {
			callSimple(mixer, "mixer/getState");
		}
	}

	@Command(name = "set-section", description = "Show or hide a mixer section.")
	static class SetSection implements Runnable {
		@ParentCommand private MixerCommand mixer;
		@Parameters(index = "0", description = "Section name (meter, io, sends, clipLauncher, devices, crossFade).")
		String section;
		@Parameters(index = "1", description = "Visibility: on or off.")
		String visibility;
		@Override
		public void run() {
			boolean visible = visibility.equalsIgnoreCase("on");
			JsonObject params = new JsonObject();
			params.addProperty("section", section);
			params.addProperty("visible", visible);
			callWithParams(mixer, "mixer/setSection", params);
		}
	}

	@Command(name = "zoom-in", description = "Zoom in all mixer channels.")
	static class ZoomIn implements Runnable {
		@ParentCommand private MixerCommand mixer;
		@Override
		public void run() {
			callSimple(mixer, "mixer/zoomInAll");
		}
	}

	@Command(name = "zoom-out", description = "Zoom out all mixer channels.")
	static class ZoomOut implements Runnable {
		@ParentCommand private MixerCommand mixer;
		@Override
		public void run() {
			callSimple(mixer, "mixer/zoomOutAll");
		}
	}

	private static void callSimple(MixerCommand mixer, String method) {
		try {
			RpcClient client = mixer.parent.createClient();
			JsonElement result = client.call(method, null);
			System.out.println(RpcClient.format(result, mixer.parent.pretty));
		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			System.exit(1);
		}
	}

	private static void callWithParams(MixerCommand mixer, String method, JsonObject params) {
		try {
			RpcClient client = mixer.parent.createClient();
			JsonElement result = client.call(method, params);
			System.out.println(RpcClient.format(result, mixer.parent.pretty));
		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			System.exit(1);
		}
	}
}
