/*-
 * #%L
 * athena-deltalake
 * %%
 * Copyright (C) 2019 - 2021 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.connectors.athena.deltalake.protocol;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;


public class DeltaTableStorage {
    Configuration parquetConf;
    AmazonS3 amazonS3;
    TableLocation tableLocation;

    public DeltaTableStorage(AmazonS3 amazonS3, Configuration parquetConf, TableLocation tableLocation) {
        this.parquetConf = parquetConf;
        this.amazonS3 = amazonS3;
        this.tableLocation = tableLocation;
    }

    public static class TableLocation {
        String bucket;
        String tableKeyPrefix;

        public TableLocation(String bucket, String tableKeyPrefix) {
            this.bucket = bucket;
            this.tableKeyPrefix = tableKeyPrefix;
        }
    }

    private String deltaLogDirectoryKey() {
        return tableLocation.tableKeyPrefix + "/_delta_log";
    }

    private String deltaLogDirectoryS3Url() {
        return String.format("s3a://%s/%s", tableLocation.bucket, deltaLogDirectoryKey());
    }

    private String extractFileName(String key) {
        String[] splitted = key.split("");
        return splitted[splitted.length - 1];
    }

    private BufferedReader openS3File(String bucket, String key)
    {
        if (amazonS3.doesObjectExist(bucket, key)) {
            S3Object obj = amazonS3.getObject(bucket, key);
            return new BufferedReader(new InputStreamReader(obj.getObjectContent()));
        }
        return null;
    }

    public DeltaTableSnapshotBuilder.CheckpointIdentifier getLastCheckpointIdentifier() throws IOException {
        String lastCheckpointFileKey = deltaLogDirectoryKey() + "/_last_checkpoint";
        BufferedReader lastCheckpointFile = openS3File(tableLocation.bucket, lastCheckpointFileKey);
        if (lastCheckpointFile == null) {
            return null;
        }
        String lastCheckpointString = lastCheckpointFile.readLine();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode actualObj = mapper.readTree(lastCheckpointString);
        long version = actualObj.get("version").asLong();
        long size = actualObj.get("size").asLong();
        Optional<Long> parts = Optional.ofNullable(actualObj.get("parts")).map(JsonNode::asLong);
        return new DeltaTableSnapshotBuilder.CheckpointIdentifier(version, size, parts);
    }

    public DeltaTableSnapshotBuilder.Checkpoint getCheckpoint(DeltaTableSnapshotBuilder.CheckpointIdentifier checkpointIdentifier) throws IOException {
        List<String> checkpointFiles = listCheckpointFiles(checkpointIdentifier);
        List<DeltaLogAction> deltaActions = new ArrayList<>();
        for(String checkpointFile: checkpointFiles) {
            String checkpointFilePath = deltaLogDirectoryS3Url() + "/" + checkpointFile;
            ParquetReader<Group> reader = ParquetReader
                    .builder(new GroupReadSupport(), new Path(checkpointFilePath))
                    .withConf(parquetConf)
                    .build();
            Group record;
            while ((record = reader.read()) != null) {
                DeltaLogAction deltaAction = parseDeltaAction(record);
                if (deltaAction != null) deltaActions.add(parseDeltaAction(record));
            }
        }
        return new DeltaTableSnapshotBuilder.Checkpoint(checkpointFiles, deltaActions);
    }

    static protected List<String> listCheckpointFiles(DeltaTableSnapshotBuilder.CheckpointIdentifier checkpointIdentifier) {
        String checkpointVersion = StringUtils.leftPad(String.valueOf(checkpointIdentifier.version), 20, '0');
        List<String> result = new ArrayList<>();
        if (checkpointIdentifier.parts.isPresent()) {
            long parts = checkpointIdentifier.parts.get();
            String partTotal = StringUtils.leftPad(String.valueOf(parts), 10, '0');
            for (long part=1 ; part<=parts ; part++) {
                String partNumber = StringUtils.leftPad(String.valueOf(part), 10, '0');
                String fileName = String.format("%s.checkpoint.%s.%s.parquet", checkpointVersion, partNumber, partTotal);
                result.add(fileName);
            }
        } else {
            String fileName = checkpointVersion + ".checkpoint.parquet";
            result.add(fileName);
        }
        return result;
    }


    public List<DeltaTableSnapshotBuilder.DeltaLogEntry> listAllDeltaLogsEntries() {
        return listDeltaLogsEntriesAfter(null);
    }

    public List<DeltaTableSnapshotBuilder.DeltaLogEntry> listDeltaLogsEntriesAfter(DeltaTableSnapshotBuilder.Checkpoint checkpoint) {
        String startAfterName = checkpoint != null ? checkpoint.fileNames.get(checkpoint.fileNames.size() - 1) : "";
        ListObjectsV2Request listRequest = new ListObjectsV2Request()
                .withBucketName(tableLocation.bucket)
                .withPrefix(deltaLogDirectoryKey() + "/")
                .withStartAfter(deltaLogDirectoryKey() + "/" + startAfterName);
        ListObjectsV2Result result = amazonS3.listObjectsV2(listRequest);
        List<String> deltaLogsKeys = result.getObjectSummaries().stream()
                .map(S3ObjectSummary::getKey)
                .filter(key -> key.endsWith(".json"))
                .sorted()
                .collect(Collectors.toList());

        List<DeltaTableSnapshotBuilder.DeltaLogEntry> deltaLogEntries = new ArrayList<>();
        for(String key: deltaLogsKeys) {
            deltaLogEntries.add(readDeltaLog(key));
        }
        return deltaLogEntries;
    }

    private DeltaTableSnapshotBuilder.DeltaLogEntry readDeltaLog(String deltaLogsEntryKey) {
        List<DeltaLogAction> deltaActions = new ArrayList<>();
        BufferedReader deltaLogsFile = openS3File(tableLocation.bucket, deltaLogsEntryKey);
        String deltaLogString;
        try {
            while ((deltaLogString = deltaLogsFile.readLine()) != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode deltaLogJson = mapper.readTree(deltaLogString);
                DeltaLogAction deltaAction = parseJsonDeltaAction(deltaLogJson);
                if (deltaAction != null) deltaActions.add(deltaAction);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new DeltaTableSnapshotBuilder.DeltaLogEntry(extractFileName(deltaLogsEntryKey), deltaActions);
    }

    private DeltaLogAction parseDeltaAction(Group deltaAction) {
        if (deltaAction.getFieldRepetitionCount("add") > 0) {
            return DeltaLogAction.AddFile.fromParquet(deltaAction.getGroup("add", 0));
        } else if (deltaAction.getFieldRepetitionCount("metaData") > 0) {
            return DeltaLogAction.MetaData.fromParquet(deltaAction.getGroup("metaData", 0));
        } else return null;
    }

    private DeltaLogAction parseJsonDeltaAction(JsonNode deltaAction) throws JsonProcessingException {
        if (deltaAction.has("add")) {
            return DeltaLogAction.AddFile.fromJsonString(deltaAction.get("add").toString());
        } else if (deltaAction.has("metaData")) {
            return DeltaLogAction.MetaData.fromJsonString(deltaAction.get("metaData").toString());
        } else if (deltaAction.has("remove")) {
            return DeltaLogAction.RemoveFile.fromJsonString(deltaAction.get("remove").toString());
        } else return null;
    }
}
