$ErrorActionPreference = 'Stop'
$cameraId = if ($env:CAMERA_ID) { $env:CAMERA_ID } else { 'dev' }
$deviceToken = $env:DEVICE_TOKEN
$streamUrl = if ($env:RTSP_URL) { $env:RTSP_URL } elseif ($deviceToken) { "rtsp://$($cameraId):$($deviceToken)@mediamtx:8554/cam/$cameraId" } else { "rtsp://mediamtx:8554/cam/$cameraId" }
$image = 'bluenviron/mediamtx:1.21.1-ffmpeg'
$ffmpegArgs = @('--rm','--network','doorcam_default','--pull','missing','--entrypoint','ffmpeg',$image,
  '-re','-f','lavfi','-i','testsrc2=size=1280x720:rate=15','-f','lavfi','-i','sine=frequency=440:sample_rate=48000',
  '-c:v','libx264','-preset','veryfast','-tune','zerolatency','-pix_fmt','yuv420p','-g','30','-b:v','1500k',
  '-c:a','libopus','-application','lowdelay','-b:a','64k','-f','rtsp','-rtsp_transport','tcp',$streamUrl)
& docker run @ffmpegArgs
exit $LASTEXITCODE

