/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.remoteexecution.grpc;

import build.bazel.remote.execution.v2.BatchUpdateBlobsRequest;
import build.bazel.remote.execution.v2.BatchUpdateBlobsResponse;
import build.bazel.remote.execution.v2.BatchUpdateBlobsResponse.Response;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.CasBlobUploader;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.event.CasBlobUploadEvent;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.Scope;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/** GRPC implementation of the CasBlobUploader. */
public class GrpcCasBlobUploader implements CasBlobUploader {

  private static final Logger LOG = Logger.get(GrpcCasBlobUploader.class);
  private static final int CHUNK_SIZE = 65536; // 64 KiB

  private final ContentAddressableStorageFutureStub storageStub;
  private final BuckEventBus buckEventBus;
  private final ByteStreamStub byteStreamStub;
  private final String instanceName;

  public GrpcCasBlobUploader(
      String instanceName,
      ContentAddressableStorageFutureStub storageStub,
      ByteStreamStub byteStreamStub,
      BuckEventBus buckEventBus,
      RemoteExecutionMetadata metadata) {
    this.instanceName = instanceName;
    this.storageStub = GrpcHeaderHandler.wrapStubToSendMetadata(storageStub, metadata);
    this.byteStreamStub = GrpcHeaderHandler.wrapStubToSendMetadata(byteStreamStub, metadata);
    this.buckEventBus = buckEventBus;
  }

  @Override
  public ImmutableSet<String> getMissingHashes(Set<Digest> requiredDigests) throws IOException {
    try {
      FindMissingBlobsRequest.Builder requestBuilder = FindMissingBlobsRequest.newBuilder();
      requiredDigests.forEach(digest -> requestBuilder.addBlobDigests((GrpcProtocol.get(digest))));
      return storageStub.findMissingBlobs(requestBuilder.build()).get().getMissingBlobDigestsList()
          .stream()
          .map(build.bazel.remote.execution.v2.Digest::getHash)
          .collect(ImmutableSet.toImmutableSet());
    } catch (InterruptedException | ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      throw new BuckUncheckedExecutionException(e);
    } catch (RuntimeException e) {
      throw e;
    }
  }

  @Override
  public ImmutableList<UploadResult> batchUpdateBlobs(ImmutableList<UploadDataSupplier> blobs)
      throws IOException {
    long totalBlobSizeBytes = blobs.stream().mapToLong(blob -> blob.getDigest().getSize()).sum();
    try (Scope ignored =
        CasBlobUploadEvent.sendEvent(buckEventBus, blobs.size(), totalBlobSizeBytes)) {
      BatchUpdateBlobsRequest.Builder requestBuilder = BatchUpdateBlobsRequest.newBuilder();
      for (UploadDataSupplier blob : blobs) {
        try (InputStream dataStream = blob.get()) {
          requestBuilder.addRequests(
              BatchUpdateBlobsRequest.Request.newBuilder()
                  .setDigest(GrpcProtocol.get(blob.getDigest()))
                  .setData(ByteString.readFrom(dataStream)));
        }
      }
      BatchUpdateBlobsResponse batchUpdateBlobsResponse =
          storageStub.batchUpdateBlobs(requestBuilder.build()).get();
      ImmutableList.Builder<UploadResult> resultBuilder = ImmutableList.builder();
      for (Response response : batchUpdateBlobsResponse.getResponsesList()) {
        resultBuilder.add(
            new UploadResult(
                new GrpcDigest(response.getDigest()),
                response.getStatus().getCode(),
                response.getStatus().getMessage()));
      }
      return resultBuilder.build();
    } catch (InterruptedException | ExecutionException e) {
      MoreThrowables.throwIfInitialCauseInstanceOf(e, IOException.class);
      throw new BuckUncheckedExecutionException(
          e,
          "When uploading a batch of blobs: <%s>.",
          blobs.stream()
              .map(b -> "[" + b.describe() + ": " + b.getDigest().toString() + "]")
              .collect(Collectors.joining(", ")));
    }
  }

  @Override
  public UploadResult uploadFromStream(UploadDataSupplier blob) throws IOException {
    long uploadSize = blob.getDigest().getSize();
    try (Scope ignored = CasBlobUploadEvent.sendEvent(buckEventBus, 1, uploadSize)) {
      InputStream dataStream = blob.get();
      String name = GrpcRemoteExecutionClients.getResourceName(instanceName, blob.getDigest());

      SettableFuture<UploadResult> result = SettableFuture.create();
      StreamObserver<ByteStreamProto.WriteResponse> responseObserver =
          new StreamObserver<ByteStreamProto.WriteResponse>() {
            @Override
            public void onNext(ByteStreamProto.WriteResponse value) {}

            @Override
            public void onError(Throwable t) {
              Status status = Status.fromThrowable(t);
              LOG.warn(
                  "Writing Digest "
                      + blob.getDigest()
                      + " to byte stream service failed: "
                      + status);
              result.set(
                  new UploadResult(blob.getDigest(), status.getCode().value(), t.getMessage()));
            }

            @Override
            public void onCompleted() {
              result.set(new UploadResult(blob.getDigest(), Status.OK.getCode().value(), ""));
            }
          };
      StreamObserver<ByteStreamProto.WriteRequest> requestObserver =
          byteStreamStub.write(responseObserver);

      // ByteString's readFrom InputStream will drain the stream - since these are large objects we
      // want read and send chunks at a time. So read byte[CHUNK_SIZE] from the InputStream and copy
      // them into the ByteString for upload.
      byte[] buffer = new byte[CHUNK_SIZE];
      int len, writeOffset = 0;
      while ((len = dataStream.read(buffer)) > 0) {
        requestObserver.onNext(
            ByteStreamProto.WriteRequest.newBuilder()
                .setResourceName(name)
                .setWriteOffset(writeOffset)
                .setData(ByteString.copyFrom(buffer, 0, len))
                .build());
        writeOffset += len;
      }
      requestObserver.onNext(
          ByteStreamProto.WriteRequest.newBuilder()
              .setResourceName(name)
              .setFinishWrite(true)
              .build());
      requestObserver.onCompleted();
      return result.get();
    } catch (InterruptedException | ExecutionException e) {
      MoreThrowables.throwIfInitialCauseInstanceOf(e, BuckUncheckedExecutionException.class);
      throw new BuckUncheckedExecutionException(
          e, "When uploading a blob: <%s>. Digests: %s.", blob.describe(), blob.getDigest());
    }
  }
}
