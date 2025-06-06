// native-lib.cpp
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <opencv2/aruco.hpp>
#include <fstream>
#include <map>
#include <vector>
#include <string>
#include <cfloat>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "native-lib", __VA_ARGS__)

std::string calibration_cache_dir;
std::string calibration_cache_hand_dir;
int saved_image_count = 0;
cv::aruco::Dictionary dict = cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50);
cv::aruco::DetectorParameters params;
cv::aruco::ArucoDetector detector(dict, params);

cv::Mat g_K       = cv::Mat::zeros(3, 3, CV_64F);
cv::Mat g_dist    = cv::Mat::zeros(1, 5, CV_64F);
cv::Mat g_Rwc     = cv::Mat::eye(3, 3, CV_64F);
cv::Mat g_twc     = cv::Mat::zeros(3, 1, CV_64F);
cv::Mat g_Kinv    = cv::Mat::zeros(3, 3, CV_64F);
bool    g_pose_valid = false;

float g_landmarks[21][3] = {0};
cv::Point3f g_landmarks_world[21] = {cv::Point3f(0,0,0)};

std::map<std::string, float> g_landmark_len;
std::map<std::string, float> landmark_len_sum;
std::map<std::string, int>   landmark_len_count;

extern "C"
JNIEXPORT void JNICALL
Java_com_example_virtualtouchpad_NativeLib_initCalibrationCache(JNIEnv *env, jobject, jstring path) {
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    calibration_cache_dir = std::string(pathStr);
    calibration_cache_hand_dir = calibration_cache_dir + "/hand";
    saved_image_count = 0;
    env->ReleaseStringUTFChars(path, pathStr);
    LOGI("Calibration cache path set: %s", calibration_cache_dir.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_virtualtouchpad_NativeLib_detectArucoMarkers(JNIEnv *env, jobject, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGI("Failed to get bitmap info or lock pixels.");
        return JNI_FALSE;
    }
    cv::Mat rgba(info.height, info.width, CV_8UC4, pixels);
    cv::Mat gray;
    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
    AndroidBitmap_unlockPixels(env, bitmap);

    std::vector<int> ids;
    std::vector<std::vector<cv::Point2f>> corners;
    detector.detectMarkers(gray, corners, ids);

    return ids.empty() ? JNI_FALSE : JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_virtualtouchpad_NativeLib_saveCalibrationImage(JNIEnv *env, jobject, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGI("Failed to get bitmap info or lock pixels.");
        return JNI_FALSE;
    }
    cv::Mat rgba(info.height, info.width, CV_8UC4, pixels);
    cv::Mat bgr;
    cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
    AndroidBitmap_unlockPixels(env, bitmap);

    cv::Mat gray;
    cv::cvtColor(bgr, gray, cv::COLOR_BGR2GRAY);

    std::vector<int> ids;
    std::vector<std::vector<cv::Point2f>> corners;
    detector.detectMarkers(gray, corners, ids);
    if (ids.empty()) return JNI_FALSE;

    char filename[512];
    snprintf(filename, sizeof(filename), "%s/img_%03d.png", calibration_cache_dir.c_str(), saved_image_count++);
    bool success = cv::imwrite(filename, bgr);
    LOGI("Image saved: %s", filename);
    return static_cast<jboolean>(success ? JNI_TRUE : JNI_FALSE);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_virtualtouchpad_NativeLib_calibrateFromSavedImages(JNIEnv *, jobject) {
    std::vector<std::vector<cv::Point3f>> objPoints;
    std::vector<std::vector<cv::Point2f>> imgPoints;
    std::vector<cv::String> files;
    cv::glob(calibration_cache_dir + "/*.png", files);
    if (files.size() < 5) {
        LOGI("Not enough images for calibration: %zu", files.size());
        return JNI_FALSE;
    }

    cv::Size imageSize(0, 0);
    bool sizeInitialized = false;
    cv::Mat img, gray;
    std::vector<cv::Point3f> marker_obj = {
            {-0.025f,  0.025f, 0},
            { 0.025f,  0.025f, 0},
            { 0.025f, -0.025f, 0},
            {-0.025f, -0.025f, 0},
    };

    for (const auto& fname : files) {
        img = cv::imread(fname);
        if (img.empty()) continue;

        if (!sizeInitialized) {
            imageSize = img.size();
            sizeInitialized = true;
        }

        cv::cvtColor(img, gray, cv::COLOR_BGR2GRAY);
        std::vector<int> ids;
        std::vector<std::vector<cv::Point2f>> corners;
        detector.detectMarkers(gray, corners, ids);
        for (size_t i = 0; i < ids.size(); ++i) {
            if (ids[i] == 0) {
                objPoints.push_back(marker_obj);
                imgPoints.push_back(corners[i]);
            }
        }
    }

    if (!sizeInitialized || objPoints.size() < 5) {
        LOGI("Insufficient data for calibration");
        return JNI_FALSE;
    }

    cv::calibrateCamera(
            objPoints,
            imgPoints,
            imageSize,
            g_K,
            g_dist,
            cv::noArray(),
            cv::noArray()
    );
    g_Kinv = g_K.inv();

    // 결과 저장
    std::string outputFile = calibration_cache_dir + "/camera_params.yml";
    cv::FileStorage fs(outputFile, cv::FileStorage::WRITE);
    fs << "K" << g_K;
    fs << "dist" << g_dist;
    fs.release();
    LOGI("Calibration complete. Saved to: %s", outputFile.c_str());
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_virtualtouchpad_NativeLib_loadCalibrationParams(JNIEnv *, jobject) {
    std::string file = calibration_cache_dir + "/camera_params.yml";
    cv::FileStorage fs(file, cv::FileStorage::READ);
    if (!fs.isOpened()) {
        LOGI("Failed to open camera_params.yml");
        return JNI_FALSE;
    }
    fs["K"]    >> g_K;
    fs["dist"] >> g_dist;
    g_Kinv = g_K.inv();
    fs.release();
    LOGI("Loaded calibration params.");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_virtualtouchpad_NativeLib_estimatePose(JNIEnv *env, jobject, jobject bitmap) {
    if (g_K.empty() || g_dist.empty()) {
        LOGI("Calibration not loaded.");
        return nullptr;
    }

    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        return nullptr;
    }
    cv::Mat rgba(info.height, info.width, CV_8UC4, pixels);
    cv::Mat gray;
    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);

    std::vector<int> ids;
    std::vector<std::vector<cv::Point2f>> corners;
    detector.detectMarkers(gray, corners, ids);

    for (size_t i = 0; i < ids.size(); ++i) {
        if (ids[i] == 0) {
            std::vector<cv::Point3f> objPoints = {
                    {-0.025f,  0.025f, 0},
                    { 0.025f,  0.025f, 0},
                    { 0.025f, -0.025f, 0},
                    {-0.025f, -0.025f, 0}
            };
            cv::Mat rvec, tvec;
            bool success = cv::solvePnP(objPoints, corners[i], g_K, g_dist, rvec, tvec);
            if (!success) continue;

            cv::Mat R;
            cv::Rodrigues(rvec, R);
            g_Rwc = R.t();
            g_twc = -g_Rwc * tvec;
            g_pose_valid = true;

            cv::Mat bgr;
            cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
            cv::drawFrameAxes(bgr, g_K, g_dist, rvec, tvec, 0.03f);
            cv::cvtColor(bgr, rgba, cv::COLOR_BGR2RGBA);

            cv::Mat viewMat = g_Rwc * (cv::Mat_<double>(3, 1) << 0, 0, -1);
            cv::Vec3d view(
                    viewMat.at<double>(0),
                    viewMat.at<double>(1),
                    viewMat.at<double>(2)
            );

            jfloatArray result = env->NewFloatArray(15);
            float data[] = {
                    (float)g_twc.at<double>(0), (float)g_twc.at<double>(1), (float)g_twc.at<double>(2),
                    (float)view[0], (float)view[1], (float)view[2],
                    (float)g_Rwc.at<double>(0,0), (float)g_Rwc.at<double>(0,1), (float)g_Rwc.at<double>(0,2),
                    (float)g_Rwc.at<double>(1,0), (float)g_Rwc.at<double>(1,1), (float)g_Rwc.at<double>(1,2),
                    (float)g_Rwc.at<double>(2,0), (float)g_Rwc.at<double>(2,1), (float)g_Rwc.at<double>(2,2)
            };
            env->SetFloatArrayRegion(result, 0, 15, data);

            AndroidBitmap_unlockPixels(env, bitmap);
            return result;
        }
    }

    g_pose_valid = false;
    AndroidBitmap_unlockPixels(env, bitmap);
    return nullptr;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_virtualtouchpad_NativeLib_uvToWorldDir(JNIEnv *env, jobject, jfloat u, jfloat v, jint imgWidth, jint imgHeight, jboolean isNormalized) {
    if (!g_pose_valid || g_Kinv.empty()) {
        return nullptr;
    }

    cv::Mat uv_h = (cv::Mat_<double>(3, 1) << u, v, 1.0);
    cv::Mat ray_cam = g_Kinv * uv_h;
    ray_cam /= cv::norm(ray_cam);
    cv::Mat ray_world = g_Rwc * ray_cam;

    jfloatArray result = env->NewFloatArray(3);
    float data[] = {
            (float)ray_world.at<double>(0),
            (float)ray_world.at<double>(1),
            (float)ray_world.at<double>(2)
    };
    env->SetFloatArrayRegion(result, 0, 3, data);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_virtualtouchpad_NativeLib_updateLandmarks(JNIEnv *env, jobject, jfloatArray arr) {
    if (env->GetArrayLength(arr) < 63) return;
    jfloat* data = env->GetFloatArrayElements(arr, nullptr);
    for (int i = 0; i < 21; ++i) {
        g_landmarks[i][0] = data[i * 3 + 0];  // u
        g_landmarks[i][1] = data[i * 3 + 1];  // v
        g_landmarks[i][2] = data[i * 3 + 2];  // z
    }
    env->ReleaseFloatArrayElements(arr, data, JNI_ABORT);
}

cv::Point3f intersectRayWithPlane(const cv::Point3f& cam_pos, const cv::Point3f& dir, float z_plane = 0.003f) {
    float t = (z_plane - cam_pos.z) / dir.z;
    return cam_pos + dir * t;
}

bool loadLandmarksFromFile(const std::string& filename,
                           cv::Point3f& cam_pos,
                           cv::Mat& rotation,
                           float landmarks[21][2]) {
    std::ifstream in(filename);
    if (!in.is_open()) return false;

    float camX, camY, camZ;
    char comma;
    if (!(in >> camX >> comma >> camY >> comma >> camZ)) return false;
    cam_pos = cv::Point3f(camX, camY, camZ);

    rotation = cv::Mat(3, 3, CV_64F);
    for (int i = 0; i < 9; ++i) {
        double val;
        if (!(in >> val)) return false;
        rotation.at<double>(i / 3, i % 3) = val;
        if (i < 8) in >> comma;
    }

    for (int i = 0; i < 21; ++i) {
        float u, v;
        if (!(in >> u >> comma >> v)) return false;
        landmarks[i][0] = u;
        landmarks[i][1] = v;
    }
    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_virtualtouchpad_NativeLib_calibrateHandFromLandmarkFiles(JNIEnv *, jobject) {
    std::vector<cv::String> files;
    cv::glob(calibration_cache_hand_dir + "/*.txt", files);

    landmark_len_sum.clear();
    landmark_len_count.clear();
    g_landmark_len.clear();

    int validCount = 0;

    for (const auto& lmkPath : files) {
        cv::Point3f cam_pos;
        cv::Mat rotation;
        float landmarks[21][2];
        if (!loadLandmarksFromFile(lmkPath, cam_pos, rotation, landmarks)) {
            LOGI("Failed to load landmark file: %s", lmkPath.c_str());
            continue;
        }

        cv::Mat Kinv_local = g_Kinv.clone();
        cv::Mat Rwc = rotation;
        cv::Point3f twc = cam_pos;

        for (int i = 0; i < 21; ++i) {
            float u = landmarks[i][0];
            float v = landmarks[i][1];
            cv::Mat uv = (cv::Mat_<double>(3, 1) << u, v, 1.0);
            cv::Mat ray_cam = Kinv_local * uv;
            ray_cam /= cv::norm(ray_cam);
            cv::Mat ray_world = Rwc * ray_cam;
            cv::Point3f dir(
                    (float)ray_world.at<double>(0),
                    (float)ray_world.at<double>(1),
                    (float)ray_world.at<double>(2)
            );
            g_landmarks_world[i] = intersectRayWithPlane(twc, dir, 0.003f);
        }

        auto calcDist = [](const cv::Point3f& a, const cv::Point3f& b) {
            return cv::norm(a - b);
        };
        auto saveLen = [&](int a, int b) {
            char key[8];
            snprintf(key, sizeof(key), "%d-%d",
                     std::min(a, b),
                     std::max(a, b));
            float len = calcDist(g_landmarks_world[a], g_landmarks_world[b]);
            landmark_len_sum[key]   += len;
            landmark_len_count[key] += 1;
        };
        saveLen(0, 1);  saveLen(1, 2);  saveLen(2, 3);  saveLen(3, 4);
        saveLen(0, 5);  saveLen(0, 9);  saveLen(0,17);
        saveLen(5, 9);  saveLen(9,13);  saveLen(13,17);
        saveLen(5, 6);  saveLen(6, 7);  saveLen(7, 8);
        saveLen(9,10);  saveLen(10,11); saveLen(11,12);
        saveLen(13,14); saveLen(14,15); saveLen(15,16);
        saveLen(17,18); saveLen(18,19); saveLen(19,20);

        ++validCount;
    }

    if (validCount == 0) {
        LOGI("No valid landmark file processed");
        return JNI_FALSE;
    }

    for (const auto& p : landmark_len_sum) {
        const std::string& key = p.first;
        float sum = p.second;
        int   cnt = landmark_len_count[key];
        if (cnt > 0) {
            g_landmark_len[key] = sum / static_cast<float>(cnt);
        }
    }

    LOGI("Hand calibration from %d landmark files complete.", validCount);
    return JNI_TRUE;
}

std::vector<cv::Point3f> intersectRaySphere(
        const cv::Point3f& center,
        float radius,
        const cv::Point3f& ray_origin,
        const cv::Point3f& ray_dir
) {
    cv::Point3f oc = ray_origin - center;
    float b = 2.0f * oc.dot(ray_dir);
    float c = oc.dot(oc) - radius * radius;
    float disc = b * b - 4 * c;
    std::vector<cv::Point3f> results;
    if (disc < 0) return results;
    float sqrt_disc = std::sqrt(disc);
    float t1 = (-b - sqrt_disc) / 2.0f;
    float t2 = (-b + sqrt_disc) / 2.0f;
    results.push_back(ray_origin + ray_dir * t1);
    if (disc > 1e-6f) // 중복 방지
        results.push_back(ray_origin + ray_dir * t2);
    return results;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_virtualtouchpad_NativeLib_estimateDepth(JNIEnv *, jobject) {
    if (!g_pose_valid) return JNI_FALSE;

    const std::vector<std::string> requiredKeys = {"0-5", "0-9", "5-9"};
    for (const auto& key : requiredKeys) {
        if (g_landmark_len.find(key) == g_landmark_len.end() || g_landmark_len[key] <= 0.0f) {
            LOGI("Missing or invalid landmark length for key: %s", key.c_str());
            return JNI_FALSE;
        }
    }

    int baseA = 0, baseB = 5, baseC = 9;
    float lenAB = g_landmark_len["0-5"];
    float lenAC = g_landmark_len["0-9"];
    float lenBC = g_landmark_len["5-9"];

    auto getRay = [](int idx) -> cv::Point3f {
        float u = g_landmarks[idx][0];
        float v = g_landmarks[idx][1];
        cv::Mat uv = (cv::Mat_<double>(3, 1) << u, v, 1.0);
        cv::Mat ray_cam = g_Kinv * uv;
        ray_cam /= cv::norm(ray_cam);
        cv::Mat ray_world = g_Rwc * ray_cam;
        return cv::Point3f(
                (float)ray_world.at<double>(0),
                (float)ray_world.at<double>(1),
                (float)ray_world.at<double>(2)
        );
    };

    cv::Point3f ray0 = getRay(baseA);
    cv::Point3f ray5 = getRay(baseB);
    cv::Point3f ray9 = getRay(baseC);
    cv::Point3f cam  = cv::Point3f(
            (float)g_twc.at<double>(0),
            (float)g_twc.at<double>(1),
            (float)g_twc.at<double>(2)
    );

    float z_min = 0.01f, z_max = 0.8f;
    int   samples = 1000, best_idx = -1;
    float best_error = FLT_MAX;
    cv::Point3f best0, best5, best9;

    for (int it = 0; it < 3; ++it) {
        float dz = (z_max - z_min) / static_cast<float>(samples - 1);
        for (int i = 0; i < samples; ++i) {
            float z = z_min + dz * i;
            cv::Point3f p0 = cam + ray0 * z;
            auto p5s = intersectRaySphere(p0, lenAB, cam, ray5);
            auto p9s = intersectRaySphere(p0, lenAC, cam, ray9);
            if (p5s.empty() || p9s.empty()) continue;

            for (const auto& p5 : p5s) {
                for (const auto& p9 : p9s) {
                    float d = cv::norm(p5 - p9);
                    float err = std::abs(d - lenBC);
                    if (err < best_error) {
                        best_error = err;
                        best_idx = i;
                        best0 = p0;
                        best5 = p5;
                        best9 = p9;
                    }
                }
            }
        }

        if (best_idx != -1) {
            int li = std::max(0, best_idx - 2);
            int hi = std::min(samples - 1, best_idx + 2);
            z_min = z_min + dz * static_cast<float>(li);
            z_max = z_min + dz * static_cast<float>(hi - li);
        }
    }

    if (best_error < 0.001f) {
        g_landmarks_world[0] = best0;
        g_landmarks_world[5] = best5;
        g_landmarks_world[9] = best9;
        LOGI("Depth estimate success: error=%.6f", best_error);
        return JNI_TRUE;
    }

    LOGI("Depth estimate failed: error=%.6f", best_error);
    return JNI_FALSE;
}

bool estimateLandmarkFromBase(int baseIdx, int targetIdx) {
    if (!g_pose_valid) return false;

    float u = g_landmarks[targetIdx][0];
    float v = g_landmarks[targetIdx][1];
    float z_target = g_landmarks[targetIdx][2];
    float z_base   = g_landmarks[baseIdx][2];

    cv::Mat uv = (cv::Mat_<double>(3, 1) << u, v, 1.0);
    cv::Mat ray_cam = g_Kinv * uv;
    ray_cam /= cv::norm(ray_cam);
    cv::Mat ray_world = g_Rwc * ray_cam;
    cv::Point3f ray(
            (float)ray_world.at<double>(0),
            (float)ray_world.at<double>(1),
            (float)ray_world.at<double>(2)
    );

    cv::Point3f origin(
            (float)g_twc.at<double>(0),
            (float)g_twc.at<double>(1),
            (float)g_twc.at<double>(2)
    );
    cv::Point3f center = g_landmarks_world[baseIdx];

    char key[8];
    snprintf(key, sizeof(key),
             "%d-%d",
             std::min(baseIdx, targetIdx),
             std::max(baseIdx, targetIdx));
    float radius = g_landmark_len[key];

    auto candidates = intersectRaySphere(center, radius, origin, ray);
    if (candidates.empty()) return false;

    if (candidates.size() == 1) {
        g_landmarks_world[targetIdx] = candidates[0];
    } else {
        if (z_target > z_base) {
            g_landmarks_world[targetIdx] = candidates[1];
        } else {
            g_landmarks_world[targetIdx] = candidates[0];
        }
    }
    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_virtualtouchpad_NativeLib_estimateIndexTip(JNIEnv *, jobject) {
    if (!estimateLandmarkFromBase(5, 6)) return JNI_FALSE;
    if (!estimateLandmarkFromBase(6, 7)) return JNI_FALSE;
    if (!estimateLandmarkFromBase(7, 8)) return JNI_FALSE;
    LOGI("Index tip estimation succeeded.");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_virtualtouchpad_NativeLib_getLandmarkWorld(JNIEnv *env, jobject, jint idx) {
    if (idx < 0 || idx >= 21) return nullptr;
    jfloatArray result = env->NewFloatArray(3);
    float data[3] = {
            g_landmarks_world[idx].x,
            g_landmarks_world[idx].y,
            g_landmarks_world[idx].z
    };
    env->SetFloatArrayRegion(result, 0, 3, data);
    return result;
}
