# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM rust:1.89-bookworm AS builder
WORKDIR /app
COPY . .
RUN cargo build --release --locked

FROM debian:bookworm-slim AS runtime
COPY --from=builder /app/target/release/app /usr/local/bin/app
ENTRYPOINT ["/usr/local/bin/app"]
