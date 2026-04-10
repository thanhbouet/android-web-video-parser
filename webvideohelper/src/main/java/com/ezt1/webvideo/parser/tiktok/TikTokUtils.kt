package com.ezt1.webvideo.parser.tiktok

import androidx.lifecycle.MutableLiveData
import com.ezt1.webvideo.parser.VideoInfo
import java.util.LinkedList
import java.util.Queue
import java.util.UUID

class TikTokUtils {

    // 1. CẤU TRÚC DỮ LIỆU ĐỂ GHÉP CẶP (FIFO)
    // Queue chứa các URL ảnh đến trước, đang chờ video tương ứng
    private val preloadThumbnails: Queue<String> = LinkedList()

    // Mảng chứa các Video đến trước, đang chờ ảnh tương ứng
    private val pendingVideos = arrayListOf<VideoInfo>()

    // 2. CẤU TRÚC DỮ LIỆU ĐỂ CHẶN TRÙNG LẶP TOÀN CỤC
    private val processedVideoUrls = mutableSetOf<String>()
    private val processedImageUrls = mutableSetOf<String>()

    // 3. TRẠNG THÁI & LIVEDATA
    var loading = false
    val completeVideos = MutableLiveData<VideoInfo>()


    // HÀM XỬ LÝ KHI NHẬN ĐƯỢC VIDEO MỚI
    fun updateVideoUrl(url: String) = synchronized(this) {

        // Cơ chế chặn Video trùng lặp toàn cục
        if (processedVideoUrls.contains(url)) {
            return@synchronized
        }
        processedVideoUrls.add(url)

        val randomUUID = UUID.randomUUID().toString().substring(6)

        val newVideo = VideoInfo(
            pageUrl = "https://www.tiktok.com/",
            url = url,
            mimeType = "video/mp4",
            title = "tiktok-$randomUUID",
            referer = "https://www.tiktok.com/",
            isDownloadable = true
        )

        // Lấy ảnh đang xếp hàng đợi (nếu có)
        val preloadedImage = preloadThumbnails.poll()

        if (preloadedImage != null) {
            // Đã có sẵn ảnh -> Ghép cặp thành công
            newVideo.thumbnail = preloadedImage
            completeVideos.postValue(newVideo)
        } else {
            // Chưa có ảnh -> Đưa video vào mảng chờ
            pendingVideos.add(newVideo)
        }
    }


    // HÀM XỬ LÝ KHI NHẬN ĐƯỢC ẢNH MỚI
    fun updateImageUrl(url: String) = synchronized(this) {

        // Cơ chế chặn Ảnh trùng lặp toàn cục
        if (processedImageUrls.contains(url)) {
            return@synchronized
        }
        processedImageUrls.add(url)

        // Tìm video ĐẦU TIÊN đang chờ ảnh trong mảng
        val waitingVideo = pendingVideos.firstOrNull()

        if (waitingVideo != null) {
            // Lắp ảnh vào video
            waitingVideo.thumbnail = url

            // Xóa video đó khỏi danh sách chờ để tối ưu bộ nhớ và vòng lặp
            pendingVideos.remove(waitingVideo)

            // Bắn event hoàn thành
            completeVideos.postValue(waitingVideo!!)
        } else {
            // Chưa có video nào đến -> Cho ảnh vào hàng đợi
            preloadThumbnails.offer(url)
        }
    }


    // HÀM DỌN DẸP BỘ NHỚ (Nên gọi khi destroy activity/fragment hoặc làm mới list)
    fun clearData() = synchronized(this) {
        preloadThumbnails.clear()
        pendingVideos.clear()
        processedVideoUrls.clear()
        processedImageUrls.clear()
        loading = false
    }
}