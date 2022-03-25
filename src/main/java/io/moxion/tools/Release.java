package io.moxion.tools;

import io.moxion.tools.commands.DeployRelease;
import io.moxion.tools.commands.InitializeRelease;
import io.moxion.tools.commands.PrepareRelease;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "release", mixinStandardHelpOptions = true, version = "checksum 4.0",
        description = "Manages the Moxion release pipeline and process",
        subcommands = { InitializeRelease.class, DeployRelease.class, PrepareRelease.class


        })
public class Release implements Callable<Boolean> {

    @Override
    public Boolean call() throws Exception {
        return null;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new Release()).execute(args);
        System.exit(exitCode);
    }
}
