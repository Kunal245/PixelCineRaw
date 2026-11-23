package com.kunal.pixelcineraw.video

/**
 * 10-Bit BT.2020 HLG HDR Video Recorder for Pixel 9a.
 * Configures MediaCodec HEVC Main10 with COLOR_STANDARD_BT2020, COLOR_TRANSFER_HLG,
 * and COLOR_RANGE_LIMITED so recorded MP4s are recognized as HDR in Google Photos.
 */
typealias HdrVideoRecorder = HevcLogRecorder
