package dev.kifuko.mctransport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricModJsonFormatTest {

    @Test
    void entrypointsUseArraysCompatibleWithFabricLoader() throws IOException {
        String json = Files.readString(Path.of("src/main/resources/fabric.mod.json"));

        assertTrue(json.contains("\"client\": ["),
                "Fabric Loader expects entrypoints.client to be an array");
        assertTrue(json.contains("\"server\": ["),
                "Fabric Loader expects entrypoints.server to be an array");
    }
}
