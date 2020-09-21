# rtsp-test

This project aims to make a reference implementation of RTSP server on Kotlin using Netty.

## Playing around with ffmpeg

There is a great native tool [ffmpeg.org](https://ffmpeg.org) that does a lot of things, including streaming. It should be able to stream to our server. With the command like below, we'll start streaming the wav-file to our server.

```bash
ffmpeg -re -stream_loop -1 -i my.wav -rtsp_transport tcp -c copy -f rtsp rtsp://localhost:8554/mystream
```

Very helpful might be to enable tracing logging by adding flag `-loglevel trace`

Though `ffmpeg` utility doesn't send the `rtpmap` attribute during announcement phase. That means it should be done manually, but didn't figure out how at the moment. Dead end :(

## Playing around with gstreamer

Another great tool to play with is [gstreamer](https://gstreamer.freedesktop.org/). To stream the file specify the following pipeline:

```bash
gst-launch-1.0 -v filesrc location=my.wav ! decodebin ! audioconvert ! rtspclientsink location=rtsp://127.0.0.1:12345 protocols=tcp debug=true
```

Unfortunately `gstreamer` seems have a [bug](https://gitlab.freedesktop.org/gstreamer/gst-rtsp-server/-/issues/88) that prevents him from sending `TEARDOWN` signal, hence the server may detect the end of the stream only when connection broken.

## Record process

`[ OPTIONS ]` -> `ANNOUNCE` -> `SETUP` -> `RECORD` -> RTP byte stream -> `[ TEARDOWN ]` or broken connection

## Links

* [RFC 2326: RTSP](https://tools.ietf.org/html/rfc2326)
* [RFC 3550: RTP](https://tools.ietf.org/html/rfc3550)
* [RFC 2327: Session Description Protocol](https://tools.ietf.org/html/rfc2327)