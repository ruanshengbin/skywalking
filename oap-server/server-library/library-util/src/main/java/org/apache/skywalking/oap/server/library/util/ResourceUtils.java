/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.library.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class ResourceUtils {

    public static Reader read(String fileName) throws FileNotFoundException {
        return new InputStreamReader(readToStream(fileName), StandardCharsets.UTF_8);
    }

    public static InputStream readToStream(String fileName) throws FileNotFoundException {
        URL url = ResourceUtils.class.getClassLoader().getResource(fileName);
        if (url == null) {
            throw new FileNotFoundException("file not found: " + fileName);
        }
        return ResourceUtils.class.getClassLoader().getResourceAsStream(fileName);
    }

    public static File[] getPathFiles(String path) throws FileNotFoundException {
        URL url = ResourceUtils.class.getClassLoader().getResource(path);
        if (url == null) {
            throw new FileNotFoundException("path not found: " + path);
        }
        return Arrays.stream(Objects.requireNonNull(new File(url.getPath()).listFiles(), "No files in " + path))
                     .filter(File::isFile).toArray(File[]::new);
    }

    public static File[] getPathFiles(String parentPath, String[] fileNames) throws FileNotFoundException {
        URL url = ResourceUtils.class.getClassLoader().getResource(parentPath);
        if (url == null) {
            throw new FileNotFoundException("path not found: " + parentPath);
        }
        final Set<String> nameSet = new HashSet<>(Arrays.asList(fileNames));
        final File[] listFiles = Objects.requireNonNull(
            new File(url.getPath())
                .listFiles((dir, name) -> nameSet.contains(name)), "No files in " + parentPath);

        if (listFiles.length == 0) {
            throw new FileNotFoundException("files not found:" + nameSet);
        }
        return listFiles;
    }

    /**
     * @param directoryPath the directory path
     * @param maxDepth      the max directory depth to get the files, the given directory is 0 as the tree root
     * @return all normal files which in this directory and subDirectory according to the maxDepth
     * @throws FileNotFoundException the directory not exist in the given path
     */
    public static List<File> getDirectoryFilesRecursive(String directoryPath,
                                                        int maxDepth) throws FileNotFoundException {

        URL url = ResourceUtils.class.getClassLoader().getResource(directoryPath);
        if (url == null) {
            throw new FileNotFoundException("path not found: " + directoryPath);
        }
        List<File> fileList = new ArrayList<>();
        return getDirectoryFilesRecursive(url.getPath(), fileList, maxDepth);
    }

    private static List<File> getDirectoryFilesRecursive(String directoryPath, List<File> fileList, int maxDepth) {
        if (maxDepth < 0) {
            return fileList;
        }
        maxDepth--;
        File file = new File(directoryPath);
        if (file.isDirectory()) {
            File[] subFiles = file.listFiles();
            if (subFiles != null) {
                for (File subFile : subFiles) {
                    if (subFile.isDirectory()) {
                        getDirectoryFilesRecursive(subFile.getPath(), fileList, maxDepth);
                    } else {
                        fileList.add(subFile);
                    }
                }
            }
        }
        return fileList;
    }
}
