package io.kevinearls.trydocker;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.output.WaitingConsumer;
import io.jenkins.tools.warpackager.lib.config.Config;
import io.jenkins.tools.warpackager.lib.impl.Builder;


public class CWPTest {
    // FIXME Change the name. I don't really understand what is the version of
    public static final String VERSION_OF_SOMETHING = "256.0-test";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Rule
    public final TestName testName = new TestName();

    private final Path resourcesDirectory = Paths.get("src", "test", "resources");

    /**
     * For each tests copy the resource files to a new temporary directory.  The only exception is casc.yml
     * For now put that in the current directory, otherwise we won't be able to find it.  (There may be
     * a way to fix this by changing the reference in packager-config.yml, but I don't know what it is.)
     *
     * TODO: simplify by just copying all files from the resources directory
     */
    @Before
    public void setUp() {
        String[] fileNames = {"casc.yml", "Jenkinsfile", "packager-config.yml"};
        List<String> filesToCopy = Arrays.asList(fileNames);
        String resourcesDirectoryBase = resourcesDirectory.toAbsolutePath().toString() + "/" + testName.getMethodName() + "/";

        for (String fileName : filesToCopy) {
            System.out.println("Copying " + fileName);
            File sourcePath = new File(resourcesDirectoryBase + "/" + fileName);
            File targetPath;
            if (fileName.equals("casc.yml")) {
                targetPath = new File("./casc.yml");
            } else {
                targetPath = new File(temporaryFolder.getRoot().getAbsolutePath() + "/" + fileName);
            }

            try {
                Files.copy(sourcePath.toPath(), targetPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Ignore for now, maybe we should log this
            }
        }
    }

    /**
     * This is a basic test of using CWP with Jenkins Configuration as Code to build a Jenkinsfile-runner.  It uses JCasC
     * to set the system message, and the Jenkinsfile verifies that it exists.
     *
     * @throws Exception
     */
    @Test
    public void testCWP() throws Exception {
        // Run the custom-war-packager.  It will create a Dockerfile and other related files
        // and leave them in temporaryFolger/output
        runCustomWarPackager();

        // Build our image.
        dockerBuild(new File(temporaryFolder.getRoot().getAbsolutePath() + "/output"));

        // And run it
        String jenkinsfileLocation = resourcesDirectory.toAbsolutePath().toString() + "/" + testName.getMethodName() + "/Jenkinsfile";
        String output = dockerRun("jenkins-experimental/jenkinsfile-runner-test-image", jenkinsfileLocation, 90, "--no-sandbox");

        System.out.println(output);   // TODO remove

        String expectedSystemMessage = "Jenkins configured automatically by Jenkins Configuration as Code Plugin";
        assertTrue("Output should contain " + expectedSystemMessage, output.contains(expectedSystemMessage));
    }

    /**
     * Run the custom-war-packager, which will create the Dockerfile and other resources we need to build our Jenkinsfile-runner instance.
     * This methods assumes packager-config.yml has been copied to temporaryFolder, and if it exists casc.yml has been copied to the current
     * directory
     *
     * @throws IOException
     * @throws InterruptedException
     */
    private void runCustomWarPackager() throws IOException, InterruptedException {
        File configurationPath = new File(temporaryFolder.getRoot().getAbsolutePath() + "/packager-config.yml");
        Config cwpConfig = Config.loadConfig(configurationPath);
        cwpConfig.buildSettings.setTmpDir(temporaryFolder.getRoot());
        cwpConfig.buildSettings.setVersion(VERSION_OF_SOMETHING);
        Builder builder = new Builder(cwpConfig);
        builder.build();
    }

    /**
     *
     * @param dockerImageName Name of the docker image we want to fun
     * @param jenksinFileLocation Absolute path to Jenkinsfile
     * @param timeoutInSeconds Timeout in seconds for this test
     * @param extraArguments Optional extra arguments
     * @return Text output from Docker run
     * @throws TimeoutException
     */
    private String dockerRun(String dockerImageName, String jenksinFileLocation, int timeoutInSeconds, String extraArguments) throws TimeoutException {
        GenericContainer gc = new GenericContainer(dockerImageName);
        gc.withFileSystemBind(jenksinFileLocation, "/workspace/Jenkinsfile");
        if (extraArguments != null) {
            gc.withCommand(extraArguments);
        }
        gc.start();

        WaitingConsumer waitingConsumer = new WaitingConsumer();
        ToStringConsumer toStringConsumer = new ToStringConsumer();
        Consumer<OutputFrame> composedConsumer = toStringConsumer.andThen(waitingConsumer);
        gc.followOutput(composedConsumer);

        waitingConsumer.waitUntilEnd(timeoutInSeconds, TimeUnit.SECONDS);

        String output = toStringConsumer.toUtf8String();
        return output;
    }

    /**
     * Build a Docker Image form the Dockerfile located in sourceDirectory
     *
     * TODO: see if this an be done with testcontainers instead.
     *
     * @param sourceDirectory location of Dockerfile and other files needed to build the image
     */
    private void dockerBuild(File sourceDirectory) throws IOException, InterruptedException, Exception {
        ProcessBuilder builder = new ProcessBuilder();
        final ProcessBuilder command = builder.command("sh", "-c", "docker build -t jenkins-experimental/jenkinsfile-runner-test-image -f Dockerfile .");
        builder.directory(sourceDirectory);
        Process process = builder.start();
        InputStreamConsumer inputStreamConsumer = new InputStreamConsumer(process.getInputStream(), System.out::println);
        Executors.newSingleThreadExecutor()
                 .submit(inputStreamConsumer);
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            BufferedReader br = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
            throw new Exception("Docker build failed with exitCode " + exitCode);
        }
    }
}


class InputStreamConsumer implements Runnable {
    private InputStream inputStream;
    private Consumer<String> consumer;

    public InputStreamConsumer (InputStream inputStream, Consumer<String> consumer) {
        this.inputStream = inputStream;
        this.consumer = consumer;
    }

    @Override
    public void run() {
        new BufferedReader(new InputStreamReader(inputStream))
                .lines()
                .forEach(consumer);
    }
}
