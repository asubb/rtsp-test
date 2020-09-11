# rtsp-test

This project aims to make a reference implementation of RTSP server on Kotlin using Netty.

## Playing around with ffmpeg

There is a great native tool [ffmpeg.org](https://ffmpeg.org) that does a lot of things, including streaming. It should be able to stream to our server. With the command like below, we'll start streaming the wav-file to our server.

```bash
ffmpeg -re -stream_loop -1 -i my.wav -rtsp_transport tcp -c copy -f rtsp rtsp://localhost:8554/mystream
```

Very helpful might be to enable tracing logging by adding flag `-loglevel trace`

## Record process

OPTIONS -> ANNOUNCE -> SETUP -> RECORD -> byte stream

## Links

* [RFC 2326: RTSP](https://tools.ietf.org/html/rfc2326)
* [RFC 3550: RTP](https://tools.ietf.org/html/rfc3550)