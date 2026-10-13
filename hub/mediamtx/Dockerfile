FROM bluenviron/mediamtx:1.21.1 AS mediamtx
FROM alpine:3.22
COPY --from=mediamtx /mediamtx /mediamtx
COPY --from=mediamtx /mediamtx.yml /mediamtx.yml
EXPOSE 8554/tcp 8889/tcp 8189/udp 9997/tcp
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:9997/v3/paths/list || exit 1
ENTRYPOINT ["/mediamtx"]