package dev.bcrick.secondo.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(
	name = "project",
	description = "Project-level controls (state, mute/solo/arm resets, cue settings).",
	mixinStandardHelpOptions = true,
	subcommands = {
		ProjectCommand.GetState.class,
		ProjectCommand.UnmuteAll.class,
		ProjectCommand.UnsoloAll.class,
		ProjectCommand.UnarmAll.class,
		ProjectCommand.SetCueVolume.class,
		ProjectCommand.SetCueMix.class
	}
)
class ProjectCommand {
	@ParentCommand SecondoCli parent;

	@Command(name = "get-state", description = "Get the current project state.")
	static class GetState implements Runnable {
		@ParentCommand private ProjectCommand project;
		@Override
		public void run() {
			callSimple(project, "project/getState");
		}
	}

	@Command(name = "unmute-all", description = "Unmute all tracks.")
	static class UnmuteAll implements Runnable {
		@ParentCommand private ProjectCommand project;
		@Override
		public void run() {
			callSimple(project, "project/unmuteAll");
		}
	}

	@Command(name = "unsolo-all", description = "Unsolo all tracks.")
	static class UnsoloAll implements Runnable {
		@ParentCommand private ProjectCommand project;
		@Override
		public void run() {
			callSimple(project, "project/unsoloAll");
		}
	}

	@Command(name = "unarm-all", description = "Unarm all tracks.")
	static class UnarmAll implements Runnable {
		@ParentCommand private ProjectCommand project;
		@Override
		public void run() {
			callSimple(project, "project/unarmAll");
		}
	}

	@Command(name = "set-cue-volume", description = "Set the cue/preview volume (0.0–1.0).")
	static class SetCueVolume implements Runnable {
		@ParentCommand private ProjectCommand project;
		@Parameters(index = "0", description = "Volume level (0.0–1.0).")
		double volume;
		@Override
		public void run() {
			JsonObject params = new JsonObject();
			params.addProperty("volume", volume);
			callWithParams(project, "project/setCueVolume", params);
		}
	}

	@Command(name = "set-cue-mix", description = "Set the cue/preview mix (0.0–1.0).")
	static class SetCueMix implements Runnable {
		@ParentCommand private ProjectCommand project;
		@Parameters(index = "0", description = "Mix level (0.0–1.0).")
		double mix;
		@Override
		public void run() {
			JsonObject params = new JsonObject();
			params.addProperty("mix", mix);
			callWithParams(project, "project/setCueMix", params);
		}
	}

	private static void callSimple(ProjectCommand project, String method) {
		try {
			RpcClient client = project.parent.createClient();
			JsonElement result = client.call(method, null);
			System.out.println(RpcClient.format(result, project.parent.pretty));
		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			System.exit(1);
		}
	}

	private static void callWithParams(ProjectCommand project, String method, JsonObject params) {
		try {
			RpcClient client = project.parent.createClient();
			JsonElement result = client.call(method, params);
			System.out.println(RpcClient.format(result, project.parent.pretty));
		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			System.exit(1);
		}
	}
}
