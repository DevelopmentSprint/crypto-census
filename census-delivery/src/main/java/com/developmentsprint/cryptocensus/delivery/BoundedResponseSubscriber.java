package com.developmentsprint.cryptocensus.delivery;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Counts and discards response bytes; remote bodies never become diagnostics or receipts. */
final class BoundedResponseSubscriber implements HttpResponse.BodySubscriber<Void> {
  static final int MAX_RESPONSE_BYTES = 64 * 1024;

  private final CompletableFuture<Void> completion = new CompletableFuture<>();
  private Flow.Subscription subscription;
  private long received;

  @Override
  public CompletionStage<Void> getBody() {
    return completion.minimalCompletionStage();
  }

  @Override
  public void onSubscribe(Flow.Subscription incoming) {
    if (subscription != null) {
      incoming.cancel();
      return;
    }
    subscription = incoming;
    subscription.request(1);
  }

  @Override
  public void onNext(List<ByteBuffer> buffers) {
    Flow.Subscription active = subscription;
    if (active == null) {
      completion.completeExceptionally(
          new IllegalStateException("Response subscription is missing"));
      return;
    }
    for (ByteBuffer buffer : buffers) {
      received += buffer.remaining();
      if (received > MAX_RESPONSE_BYTES) {
        active.cancel();
        completion.completeExceptionally(new ResponseTooLargeException());
        return;
      }
    }
    active.request(1);
  }

  @Override
  public void onError(Throwable error) {
    completion.completeExceptionally(error);
  }

  @Override
  public void onComplete() {
    completion.complete(null);
  }

  static final class ResponseTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    ResponseTooLargeException() {
      super("Destination response exceeded the permitted size");
    }
  }
}
