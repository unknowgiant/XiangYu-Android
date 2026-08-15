package cn.xiangyu.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.location.Location;
import android.location.Address;
import android.location.Geocoder;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_EXPORT_NOTEBOOK = 71;
    private static final int REQUEST_IMPORT_NOTEBOOK = 72;
    private static final int MAX_NOTEBOOK_BYTES = 2 * 1024 * 1024;
    private XiangYuView content;
    private LocationManager locationManager;
    private CityRepository cityRepository;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(0xfffbf6f3);
        window.setNavigationBarColor(0xfffbf6f3);
        cityRepository = new CityRepository(this);
        LocalData.validateCoverage(cityRepository.all());
        content = new XiangYuView(this);
        setContentView(content);
        // Some emulator ROMs expose API 30 but crash inside Window.getInsetsController().
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        content.setCity(cityRepository.findByName("上海"), true);
        // Android 16 permission controllers may dim the launch window before the first frame.
        // Auto-locate only when permission already exists; otherwise keep the home screen usable.
        content.post(() -> new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                findLocation();
            } else {
                content.locationLabel = "点此选城市";
                content.invalidate();
            }
        }, 300));
    }

    void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 42);
            return;
        }
        findLocation();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 42 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            findLocation();
        } else {
            content.locationLabel = "默认城市";
            content.invalidate();
            Toast.makeText(this, "未获得位置权限，可点右上角手动选择城市", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void findLocation() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        for (String provider : locationManager.getProviders(true)) {
            Location candidate = locationManager.getLastKnownLocation(provider);
            if (candidate != null && (best == null || candidate.getAccuracy() < best.getAccuracy())) best = candidate;
        }
        if (best != null) useLocation(best);
        try {
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                @Override public void onLocationChanged(Location location) { useLocation(location); }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) { }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            }, getMainLooper());
        } catch (Exception ignored) {
            content.locationLabel = "定位不可用";
            content.invalidate();
        }
    }

    private void useLocation(Location location) {
        new Thread(() -> {
            CityRepository.City city = null;
            try {
                List<Address> addresses = new Geocoder(this, Locale.CHINA)
                    .getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    city = cityRepository.findByName(address.getLocality(), address.getSubAdminArea(), address.getAdminArea());
                }
            } catch (Exception ignored) { }
            if (city == null) city = cityRepository.nearest(location.getLatitude(), location.getLongitude());
            CityRepository.City resolved = city;
            runOnUiThread(() -> {
                content.locationLabel = "已定位";
                content.setCity(resolved, false);
            });
        }).start();
    }

    void chooseCity() {
        List<String> provinces = cityRepository.provinces();
        new AlertDialog.Builder(this).setTitle("选择省份")
            .setItems(provinces.toArray(new String[0]), (dialog, which) -> {
                List<CityRepository.City> cities = cityRepository.inProvince(provinces.get(which));
                String[] names = new String[cities.size()];
                for (int i = 0; i < cities.size(); i++) names[i] = cities.get(i).name;
                new AlertDialog.Builder(this).setTitle(provinces.get(which))
                    .setItems(names, (cityDialog, cityIndex) -> {
                        content.locationLabel = "手动选择";
                        content.setCity(cities.get(cityIndex), false);
                    }).setNegativeButton("取消", null).show();
            }).setPositiveButton("定位当前城市", (dialog, which) -> requestLocation())
            .setNegativeButton("取消", null).show();
    }

    void chooseDate() {
        LocalDate date = content.date;
        new DatePickerDialog(this, (view, year, month, day) -> {
            content.date = LocalDate.of(year, month + 1, day);
            content.invalidate();
        }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show();
    }

    void exportNotebook() {
        if (content.userContent.notebookEntryCount() == 0) {
            Toast.makeText(this, "还没有可导出的笔记", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "乡遇记事本-"
            + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".json");
        startActivityForResult(intent, REQUEST_EXPORT_NOTEBOOK);
    }

    void importNotebook() {
        new AlertDialog.Builder(this).setTitle("导入共享记事本")
            .setMessage("导入会与本机笔记合并；相同条目的导入笔记将覆盖本机旧笔记，其他内容不会删除。")
            .setPositiveButton("选择文件", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, REQUEST_IMPORT_NOTEBOOK);
            }).setNegativeButton("取消", null).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_EXPORT_NOTEBOOK) {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("无法写入所选文件");
                    output.write(content.userContent.exportNotebook().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Toast.makeText(this, "记事本已导出，可发送给其他人使用", Toast.LENGTH_LONG).show();
            } else if (requestCode == REQUEST_IMPORT_NOTEBOOK) {
                String source = readNotebook(uri);
                UserContentStore.ImportResult result = content.userContent.importNotebook(source);
                content.invalidate();
                String message = result.total() == 0
                    ? "没有可导入的新笔记" : "已导入 " + result.total() + " 条：新增 "
                        + result.added + " 条，更新 " + result.updated + " 条";
                if (result.skipped > 0) message += "，跳过 " + result.skipped + " 条";
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        } catch (Exception error) {
            String message = error.getMessage();
            Toast.makeText(this, message == null || message.isEmpty()
                ? "记事本文件处理失败" : message, Toast.LENGTH_LONG).show();
        }
    }

    private String readNotebook(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("无法读取所选文件");
            byte[] buffer = new byte[8192];
            int read, total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_NOTEBOOK_BYTES) throw new IllegalArgumentException("记事本文件不能超过 2MB");
                output.write(buffer, 0, read);
            }
            return output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }

    static final class XiangYuView extends View {
        private static final int PAPER = 0xfff8f6f1;
        private static final int INK = 0xff302e2b;
        private static final int MUTED = 0xff77736e;
        private static final int RED = 0xffbd6255;
        private static final int GREEN = 0xff39745f;
        private static final int LINE = 0xffe3ded5;
        private static final float HEADER_TOP_DP = 48f;
        private static final float WEATHER_HEIGHT_DP = 138f;
        private static final float TODAY_WEATHER_GAP_DP = 10f;
        private static final float TODAY_LANDMARK_GAP_DP = 12f;
        private static final float PHOTO_BOTTOM_SPACE_DP = 20f;
        private static final float MIN_PHOTO_HEIGHT_DP = 96f;
        private static final float DISCOVER_TAB_SHIFT_PX = 10f;
        private static final int[] DISCOVER_CATEGORY_ORDER = {0, 1, 2, 4, 5, 3};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float baseDensity;
        private float density;
        private final SharedPreferences prefs;
        private final Set<String> favorites;
        private final Map<String, LocalData.Item> favoriteItems = new HashMap<>();
        private final List<RectF> heartBounds = new ArrayList<>();
        private final List<RectF> itemBounds = new ArrayList<>();
        private final List<LocalData.Item> visibleItems = new ArrayList<>();
        private final List<RectF> tabBounds = new ArrayList<>();
        private final UserContentStore userContent;
        private RectF addBounds;
        private RectF photoBounds;
        private RectF importNotebookBounds;
        private RectF exportNotebookBounds;
        private final List<PhotoService.Photo> landmarkPhotos = new ArrayList<>();
        private int photoIndex;
        private boolean photosLoading;
        private boolean photosFailed;
        private int photoRequestGeneration;
        private LocalData.Place place;
        private CityRepository.City currentCity;
        private String currentCityCode = "";
        private WeatherService.Weather weather = new WeatherService.Weather(24, 28, 20, 1, 62, 2.4, false);
        private String locationLabel = "正在定位";
        private LocalDate date = LocalDate.now();
        private int contentTab = 0;
        private int navTab = 0;
        private float scrollY = 0;
        private float lastY;
        private float downX;
        private float downY;
        private boolean dragging;
        private float contentHeight;

        XiangYuView(Context context) {
            super(context);
            baseDensity = getResources().getDisplayMetrics().density;
            density = baseDensity;
            prefs = context.getSharedPreferences("favorites", MODE_PRIVATE);
            userContent = new UserContentStore(context);
            favorites = new HashSet<>(prefs.getStringSet("ids", new HashSet<>()));
            restoreFavoriteItems();
            for (LocalData.Item item : LocalData.allItems()) {
                if (favorites.contains(item.id)) favoriteItems.put(item.id, item);
            }
            setBackgroundColor(PAPER);
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            if (w <= 0 || h <= 0) return;
            float widthDp = w / baseDensity;
            float heightDp = h / baseDensity;
            float compactScale = clamp(widthDp / 360f, 0.78f, 1f);
            if (w > h) compactScale = Math.min(compactScale, clamp(heightDp / 600f, 0.72f, 1f));
            density = baseDensity * compactScale;
            scrollY = 0;
        }

        void setCity(CityRepository.City value, boolean initial) {
            currentCity = value;
            currentCityCode = value.code;
            place = LocalData.forCity(value);
            landmarkPhotos.clear(); photoIndex = 0; photosLoading = true; photosFailed = false;
            scrollY = 0;
            invalidate();
            refreshLandmarkPhotos(value, place.sights);
            WeatherService.fetch(value.lat, value.lon, result -> post(() -> {
                if (!currentCityCode.equals(value.code)) return;
                weather = result;
                invalidate();
                if (!initial && !result.fresh) Toast.makeText(getContext(), "网络暂不可用，天气为参考数据", Toast.LENGTH_SHORT).show();
            }));
            HotelRankService.fetch(value, ranked -> post(() -> {
                if (!currentCityCode.equals(value.code)) return;
                place = LocalData.withRankedHotels(place, value, ranked.hotels, ranked.sources);
                invalidate();
            }));
            TravelTipService.fetch(value, result -> post(() -> {
                if (!currentCityCode.equals(value.code) || result.tips.isEmpty()) return;
                place = LocalData.withTravelTips(place, result.tips);
                invalidate();
            }));
        }

        private void refreshLandmarkPhotos(CityRepository.City city, List<LocalData.Item> sights) {
            int generation = ++photoRequestGeneration;
            landmarkPhotos.clear(); photoIndex = 0; photosLoading = true; photosFailed = false;
            PhotoService.fetch(city, sights, photos -> post(() -> {
                if (!currentCityCode.equals(city.code) || generation != photoRequestGeneration) return;
                landmarkPhotos.clear(); landmarkPhotos.addAll(photos);
                photosLoading = photos.isEmpty() && !photosFailed;
                if (!photos.isEmpty()) photosFailed = false;
                invalidate(); scheduleNextPhoto();
            }));
            postDelayed(() -> {
                if (!currentCityCode.equals(city.code) || generation != photoRequestGeneration
                        || !landmarkPhotos.isEmpty()) return;
                photosLoading = false; photosFailed = true; invalidate();
            }, 30000);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (place == null) return;
            float w = getWidth();
            float bottomBar = dp(72);
            float headerTop = dp(HEADER_TOP_DP);
            float contentTop = headerTop + dp(60);
            heartBounds.clear();
            itemBounds.clear();
            visibleItems.clear();
            tabBounds.clear();
            addBounds = null;
            photoBounds = null;
            importNotebookBounds = null;
            exportNotebookBounds = null;
            if (navTab == 1) {
                float listTop = drawDiscoverNavigation(canvas, w, contentTop + dp(10));
                canvas.save();
                canvas.clipRect(0, listTop, w, getHeight() - bottomBar);
                canvas.translate(0, -scrollY);
                contentHeight = drawDiscoverItems(canvas, w, listTop) + dp(20);
                canvas.restore();
            } else {
                canvas.save();
                canvas.clipRect(0, contentTop, w, getHeight() - bottomBar);
                canvas.translate(0, -scrollY);
                float y = navTab == 0 ? drawToday(canvas, w, contentTop) : drawSaved(canvas, w, contentTop);
                contentHeight = y + (navTab == 0 ? 0 : dp(20));
                canvas.restore();
            }
            drawHeader(canvas, w, headerTop);
            drawBottomNav(canvas, w);
        }

        private float drawHeader(Canvas c, float w, float y) {
            text(c, "乡遇", dp(25), RED, dp(20), y + dp(25), true);
            text(c, "在一方水土，遇见一方生活", dp(11), MUTED, dp(20), y + dp(44), false);
            float pillW = dp(118);
            roundRect(c, w - pillW - dp(18), y + dp(8), w - dp(18), y + dp(38), dp(15), 0xffeeebe3);
            pin(c, w - pillW - dp(6), y + dp(23), RED);
            String cityText = place.city.length() > 6 ? place.city.substring(0, 6) + "…" : place.city;
            text(c, cityText + " · " + locationLabel, dp(10), INK, w - pillW + dp(7), y + dp(27), false);
            line(c, dp(18), y + dp(58), w - dp(18), y + dp(58), LINE, 1);
            return y + dp(60);
        }

        private float drawToday(Canvas c, float w, float y) {
            y = drawWeather(c, w, y);
            y = drawCalendar(c, w, y + dp(TODAY_WEATHER_GAP_DP));
            y = drawLandmarkPhotos(c, w, y + dp(TODAY_LANDMARK_GAP_DP));
            return y;
        }

        private float drawWeather(Canvas c, float w, float y) {
            float l = dp(16), r = w - dp(16), h = dp(WEATHER_HEIGHT_DP);
            roundRect(c, l, y, r, y + h, dp(6), GREEN);
            // A restrained landscape motif gives weather a strong, local visual anchor.
            paint.setColor(0x18ffffff);
            path.reset(); path.moveTo(l, y + h); path.lineTo(l, y + dp(92)); path.lineTo(l + dp(65), y + dp(55));
            path.lineTo(l + dp(135), y + dp(108)); path.lineTo(l + dp(200), y + dp(68)); path.lineTo(r, y + dp(103)); path.lineTo(r, y + h); path.close(); c.drawPath(path, paint);
            sun(c, r - dp(48), y + dp(39), weather.code);
            text(c, weather.description(), dp(12), 0xddffffff, l + dp(20), y + dp(26), false);
            text(c, weather.temperature + "°", dp(47), Color.WHITE, l + dp(18), y + dp(76), true);
            text(c, weather.low + "° / " + weather.high + "°", dp(12), 0xeeffffff, l + dp(84), y + dp(69), false);
            line(c, l + dp(20), y + dp(92), r - dp(20), y + dp(92), 0x36ffffff, 1);
            float footerWidth = r - l;
            textCenter(c, "湿度 " + weather.humidity + "%", dp(10), 0xddffffff,
                l + footerWidth / 6f, y + dp(119), false);
            textCenter(c, "风速 " + String.format(Locale.CHINA, "%.1f km/h", weather.wind), dp(10), 0xddffffff,
                l + footerWidth / 2f, y + dp(119), false);
            textCenter(c, weather.fresh ? "刚刚更新" : "参考天气", dp(9), 0xaaffffff,
                l + footerWidth * 5f / 6f, y + dp(119), false);
            return y + h;
        }

        private float drawCalendar(Canvas c, float w, float y) {
            CalendarInfo info = CalendarInfo.of(date);
            float l = dp(16), r = w - dp(16), h = dp(190);
            roundRect(c, l, y, r, y + h, dp(6), Color.WHITE);
            text(c, "‹", dp(27), MUTED, l + dp(18), y + dp(34), false);
            textRight(c, "›", dp(27), MUTED, r - dp(18), y + dp(34), false);
            DateTimeFormatter month = DateTimeFormatter.ofPattern("yyyy年 M月", Locale.CHINA);
            textCenter(c, date.format(month), dp(13), INK, w / 2, y + dp(25), true);
            String week = "星期" + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1);
            text(c, String.valueOf(date.getDayOfMonth()), dp(43), RED, l + dp(20), y + dp(78), true);
            text(c, week, dp(11), MUTED, l + dp(75), y + dp(49), false);
            text(c, info.lunarDate, dp(18), INK, l + dp(75), y + dp(70), true);
            text(c, info.lunarYear, dp(10), MUTED, l + dp(75), y + dp(89), false);
            String badge = !info.festival.isEmpty() ? info.festival : (!info.solarTerm.isEmpty() ? info.solarTerm : "今日");
            roundRect(c, r - dp(73), y + dp(43), r - dp(20), y + dp(68), dp(3), 0xfff2e1dc);
            textCenter(c, badge, dp(11), RED, r - dp(46), y + dp(60), true);
            line(c, l + dp(20), y + dp(100), r - dp(20), y + dp(100), LINE, 1);
            circle(c, l + dp(30), y + dp(121), dp(10), 0xffe4eee8);
            textCenter(c, "宜", dp(9), GREEN, l + dp(30), y + dp(125), true);
            text(c, fitWidth(info.suitable, dp(10), r - l - dp(74)), dp(10), INK, l + dp(49), y + dp(124), false);
            circle(c, l + dp(30), y + dp(147), dp(10), 0xfff3e3df);
            textCenter(c, "忌", dp(9), RED, l + dp(30), y + dp(151), true);
            text(c, fitWidth(info.avoid, dp(10), r - l - dp(74)), dp(10), INK, l + dp(49), y + dp(150), false);
            line(c, l + dp(20), y + dp(163), r - dp(20), y + dp(163), LINE, 1);
            text(c, fitWidth(info.season + " · " + info.observance + " · 民俗参考", dp(9), (r - l) * 0.62f),
                dp(9), GREEN, l + dp(20), y + dp(181), true);
            textRight(c, info.weekInfo, dp(9), MUTED, r - dp(20), y + dp(181), false);
            return y + h;
        }

        private float drawLandmarkPhotos(Canvas c, float w, float y) {
            text(c, place.city + "著名景点", dp(21), INK, dp(18), y + dp(23), true);
            text(c, "城市影像", dp(11), MUTED, dp(18), y + dp(43), false);
            float l = dp(16), r = w - dp(16), top = y + dp(58);
            float desiredHeight = clamp((r - l) * 0.62f, dp(210), dp(360));
            float visibleHeight = getHeight() - dp(72) - top - dp(PHOTO_BOTTOM_SPACE_DP);
            float h = Math.min(desiredHeight, Math.max(dp(MIN_PHOTO_HEIGHT_DP), visibleHeight));
            photoBounds = new RectF(l, top, r, top + h);
            roundRect(c, l, top, r, top + h, dp(6), 0xffe7e5df);
            if (!landmarkPhotos.isEmpty()) {
                PhotoService.Photo photo = landmarkPhotos.get(photoIndex % landmarkPhotos.size());
                drawCoverBitmap(c, photo.bitmap, photoBounds);
                paint.setColor(0x99000000); c.drawRect(l, top + h - dp(56), r, top + h, paint);
                roundRect(c, r - dp(55), top + dp(12), r - dp(12), top + dp(35), dp(4), 0x99000000);
                textCenter(c, (photoIndex + 1) + " / " + landmarkPhotos.size(), dp(10), Color.WHITE,
                    r - dp(33.5f), top + dp(27), true);
                text(c, fit(photo.title, 22), dp(17), Color.WHITE, l + dp(16), top + h - dp(29), true);
                text(c, photo.source, dp(9), 0xddffffff, l + dp(16), top + h - dp(11), false);
                for (int i = 0; i < landmarkPhotos.size(); i++) {
                    circle(c, w / 2 + (i - (landmarkPhotos.size() - 1) / 2f) * dp(12), top + h + dp(13),
                        dp(i == photoIndex ? 3.3f : 2.4f), i == photoIndex ? RED : 0xffb9b7b0);
                }
            } else {
                String status = photosLoading ? "正在从互联网获取景点照片…" : "暂未获取到当地景点照片";
                textCenter(c, status, dp(13), MUTED, w / 2, top + dp(98), false);
                textCenter(c, photosFailed ? "请检查网络后重新选择城市刷新" : "图片加载后将自动轮播", dp(10), MUTED,
                    w / 2, top + dp(121), false);
            }
            return top + h + dp(PHOTO_BOTTOM_SPACE_DP);
        }

        private void drawCoverBitmap(Canvas c, Bitmap bitmap, RectF target) {
            float sourceRatio = bitmap.getWidth() / (float) bitmap.getHeight();
            float targetRatio = target.width() / target.height();
            Rect source;
            if (sourceRatio > targetRatio) {
                int width = Math.round(bitmap.getHeight() * targetRatio);
                int left = (bitmap.getWidth() - width) / 2;
                source = new Rect(left, 0, left + width, bitmap.getHeight());
            } else {
                int height = Math.round(bitmap.getWidth() / targetRatio);
                int top = (bitmap.getHeight() - height) / 2;
                source = new Rect(0, top, bitmap.getWidth(), top + height);
            }
            c.save(); path.reset(); path.addRoundRect(target, dp(6), dp(6), Path.Direction.CW); c.clipPath(path);
            c.drawBitmap(bitmap, source, target, paint); c.restore();
        }

        private void scheduleNextPhoto() {
            removeCallbacks(photoAdvance);
            if (landmarkPhotos.size() > 1) postDelayed(photoAdvance, 4500);
        }

        private final Runnable photoAdvance = new Runnable() {
            @Override public void run() {
                if (landmarkPhotos.size() > 1) {
                    photoIndex = (photoIndex + 1) % landmarkPhotos.size();
                    if (navTab == 0) invalidate();
                    postDelayed(this, 4500);
                }
            }
        };

        private float drawDiscoverNavigation(Canvas c, float w, float y) {
            paint.setColor(PAPER);
            c.drawRect(0, y - dp(10), w, y + dp(108), paint);
            text(c, "此地风物", dp(21), INK, dp(18), y + dp(23), true);
            text(c, place.intro, dp(11), MUTED, dp(18), y + dp(43), false);
            if (contentTab != 5) {
                addBounds = new RectF(w - dp(55), y, w - dp(16), y + dp(40));
                circle(c, addBounds.centerX(), y + dp(20), dp(15), RED);
                textCenter(c, "+", dp(22), Color.WHITE, addBounds.centerX(), y + dp(27), false);
            }
            y += dp(58) + DISCOVER_TAB_SHIFT_PX;
            String[] tabs = {"小吃", "风俗", "景区", "酒店", "交通", "避坑"};
            float gap = dp(4), l = dp(16), tabW = (w - dp(32) - gap * 5) / 6;
            for (int i = 0; i < tabs.length; i++) {
                int category = DISCOVER_CATEGORY_ORDER[i];
                float x = l + i * (tabW + gap);
                RectF tab = new RectF(x, y, x + tabW, y + dp(36));
                tabBounds.add(tab);
                roundRect(c, tab.left, tab.top, tab.right, tab.bottom, dp(6), category == contentTab ? RED : 0xffeeeae2);
                textCenter(c, tabs[i], dp(10), category == contentTab ? Color.WHITE : MUTED,
                    x + tabW / 2, y + dp(23), category == contentTab);
                if (category == contentTab) {
                    roundRect(c, x + tabW * 0.28f, y + dp(40), x + tabW * 0.72f,
                        y + dp(43), dp(1.5f), RED);
                }
            }
            return y + dp(50);
        }

        private float drawDiscoverItems(Canvas c, float w, float y) {
            List<LocalData.Item> items = contentTab == 5
                ? TransportService.items(((MainActivity) getContext()).cityRepository, currentCity, place.sights)
                : userContent.apply(currentCityCode, contentTab, LocalData.items(place, contentTab));
            for (LocalData.Item item : items) {
                y = drawItem(c, w, y, item);
            }
            return y;
        }

        private float drawSaved(Canvas c, float w, float y) {
            text(c, "我的收藏", dp(24), INK, dp(18), y + dp(28), true);
            text(c, "个人笔记可导出分享，也可合并他人的记事本", dp(11), MUTED, dp(18), y + dp(49), false);
            float gap = dp(8), left = dp(16), right = w - dp(16), buttonTop = y + dp(62);
            float buttonWidth = (right - left - gap) / 2f;
            importNotebookBounds = new RectF(left, buttonTop, left + buttonWidth, buttonTop + dp(42));
            exportNotebookBounds = new RectF(left + buttonWidth + gap, buttonTop, right, buttonTop + dp(42));
            roundRect(c, importNotebookBounds.left, importNotebookBounds.top,
                importNotebookBounds.right, importNotebookBounds.bottom, dp(4), 0xffebe8e0);
            roundRect(c, exportNotebookBounds.left, exportNotebookBounds.top,
                exportNotebookBounds.right, exportNotebookBounds.bottom, dp(4), RED);
            textCenter(c, "导入记事本", dp(11), INK, importNotebookBounds.centerX(), buttonTop + dp(26), true);
            textCenter(c, "导出记事本", dp(11), Color.WHITE, exportNotebookBounds.centerX(), buttonTop + dp(26), true);
            y += dp(120);
            if (favorites.isEmpty()) {
                roundRect(c, dp(16), y, w - dp(16), y + dp(150), dp(6), Color.WHITE);
                bookmark(c, w / 2, y + dp(48), MUTED, false);
                textCenter(c, "暂无收藏", dp(15), INK, w / 2, y + dp(91), true);
                textCenter(c, "笔记仍可在发现页的条目中添加", dp(11), MUTED, w / 2, y + dp(114), false);
                return y + dp(160);
            }
            for (LocalData.Item snapshot : favoriteItems.values()) {
                if (!favorites.contains(snapshot.id)) continue;
                UserContentStore.Record record = userContent.find(snapshot.id);
                LocalData.Item item = resolveFavorite(snapshot, record);
                y = drawItem(c, w, y, item);
            }
            return y;
        }

        private LocalData.Item resolveFavorite(LocalData.Item snapshot, UserContentStore.Record record) {
            if (record == null || record.custom || record.contentEdited) {
                return record == null ? snapshot : record.asItem();
            }
            CityRepository.City city = ((MainActivity) getContext()).cityRepository.findByCode(record.cityCode);
            if (city == null) return snapshot;
            for (LocalData.Item item : LocalData.items(LocalData.forCity(city), record.category)) {
                if (item.id.equals(record.itemId)) return item;
            }
            return snapshot;
        }

        private float drawItem(Canvas c, float w, float y, LocalData.Item item) {
            boolean transportRow = navTab == 1 && contentTab == 5;
            float l = dp(16), r = w - dp(16), h = dp(106);
            roundRect(c, l, y, r, y + h, dp(6), Color.WHITE);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(LINE);
            c.drawRoundRect(new RectF(l, y, r, y + h), dp(6), dp(6), paint);
            paint.setStyle(Paint.Style.FILL);
            roundRect(c, l + dp(10), y + dp(10), l + dp(88), y + h - dp(10), dp(4), item.color);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0x3affffff);
            c.drawCircle(l + dp(49), y + dp(53), dp(28), paint); paint.setStyle(Paint.Style.FILL);
            textCenter(c, item.mark, dp(29), Color.WHITE, l + dp(49), y + dp(63), true);
            float textLeft = l + dp(102), textRight = r - dp(52);
            text(c, fitWidth(item.title, dp(16), textRight - textLeft), dp(16), INK, textLeft, y + dp(29), true);
            String[] description = wrapTwoLines(item.subtitle, dp(10), r - dp(18) - textLeft);
            text(c, description[0], dp(10), MUTED, textLeft, y + dp(51), false);
            text(c, description[1], dp(10), MUTED, textLeft, y + dp(66), false);
            text(c, fitWidth(item.meta, dp(10), r - dp(18) - textLeft), dp(10), item.color, textLeft, y + dp(88), true);
            RectF heart = new RectF(r - dp(43), y + dp(10), r - dp(9), y + dp(44));
            heartBounds.add(transportRow ? new RectF(-1, -1, -1, -1) : heart);
            itemBounds.add(new RectF(l, y, r, y + h));
            visibleItems.add(item);
            if (!transportRow) {
                heart(c, heart.centerX(), heart.centerY(), favorites.contains(item.id) ? RED : 0xffa7aaa2, favorites.contains(item.id));
            }
            if (!transportRow && !userContent.note(item.id).isEmpty()) {
                textRight(c, "有笔记", dp(9), GREEN, r - dp(12), y + dp(88), true);
            } else if (navTab == 1 && contentTab == 2) {
                textRight(c, "查看周边", dp(9), GREEN, r - dp(12), y + dp(88), true);
            }
            return y + h + dp(10);
        }

        private void drawBottomNav(Canvas c, float w) {
            float top = getHeight() - dp(72);
            paint.setColor(0xffffffff); c.drawRect(0, top, w, getHeight(), paint);
            line(c, 0, top, w, top, LINE, 1);
            String[] labels = {"今日", "发现", "收藏"};
            for (int i = 0; i < 3; i++) {
                float x = w * (i * 2 + 1) / 6;
                int color = navTab == i ? RED : MUTED;
                if (navTab == i) roundRect(c, x - dp(18), top + dp(3), x + dp(18), top + dp(6), dp(1.5f), RED);
                if (i == 0) calendarIcon(c, x, top + dp(24), color);
                else if (i == 1) compass(c, x, top + dp(24), color);
                else bookmark(c, x, top + dp(24), color, navTab == 2);
                textCenter(c, labels[i], dp(10), color, x, top + dp(51), navTab == i);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float y = event.getY();
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                lastY = y; downY = y; downX = event.getX(); dragging = false; return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float delta = lastY - y;
                if (Math.abs(y - downY) > dp(5)) dragging = true;
                scrollY = clamp(scrollY + delta, 0, Math.max(0, contentHeight - (getHeight() - dp(72))));
                lastY = y; invalidate(); return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP && !dragging) {
                float x = event.getX();
                if (y > getHeight() - dp(72)) {
                    int chosen = Math.min(2, (int) (x / (getWidth() / 3f)));
                    if (chosen == 0 && navTab == 0) ((MainActivity) getContext()).chooseDate();
                    navTab = chosen;
                    scrollY = 0;
                    invalidate(); return true;
                }
                float headerTop = dp(HEADER_TOP_DP);
                float contentTop = headerTop + dp(60);
                if (y >= headerTop && y <= headerTop + dp(48) && x > getWidth() - dp(155)) {
                    ((MainActivity) getContext()).chooseCity(); return true;
                }
                if (y < contentTop) return true;
                float localY = y + scrollY;
                if (navTab == 0) {
                    float calendarTop = contentTop + dp(WEATHER_HEIGHT_DP + TODAY_WEATHER_GAP_DP);
                    if (localY > calendarTop && localY < calendarTop + dp(54)) {
                        if (x < getWidth() / 3f) date = date.minusDays(1);
                        else if (x > getWidth() * 2f / 3f) date = date.plusDays(1);
                        else ((MainActivity) getContext()).chooseDate();
                        invalidate(); return true;
                    }
                    if (photoBounds != null && photoBounds.contains(x, localY) && landmarkPhotos.size() > 1) {
                        photoIndex = Math.floorMod(photoIndex + (x < getWidth() / 2f ? -1 : 1), landmarkPhotos.size());
                        scheduleNextPhoto(); invalidate(); return true;
                    }
                }
                if (navTab == 1) {
                    if (addBounds != null && addBounds.contains(x, y)) {
                        showEditDialog(null, true); return true;
                    }
                    for (int i = 0; i < tabBounds.size(); i++) {
                        if (tabBounds.get(i).contains(x, y)) {
                            contentTab = DISCOVER_CATEGORY_ORDER[i]; scrollY = 0; invalidate(); return true;
                        }
                    }
                }
                if (navTab == 2) {
                    if (importNotebookBounds != null && importNotebookBounds.contains(x, localY)) {
                        ((MainActivity) getContext()).importNotebook(); return true;
                    }
                    if (exportNotebookBounds != null && exportNotebookBounds.contains(x, localY)) {
                        ((MainActivity) getContext()).exportNotebook(); return true;
                    }
                }
                for (int i = 0; i < heartBounds.size(); i++) {
                    if (heartBounds.get(i).contains(x, localY)) {
                        if (i >= visibleItems.size()) return true;
                        LocalData.Item target = visibleItems.get(i);
                        if (!favorites.add(target.id)) favorites.remove(target.id);
                        favoriteItems.put(target.id, target);
                        prefs.edit().putStringSet("ids", new HashSet<>(favorites)).apply();
                        persistFavoriteItems();
                        invalidate(); return true;
                    }
                }
                for (int i = 0; i < itemBounds.size(); i++) {
                    if (itemBounds.get(i).contains(x, localY)) {
                        LocalData.Item item = visibleItems.get(i);
                        if (navTab == 1) showItemDetails(item, contentTab);
                        else showItemMenu(item);
                        return true;
                    }
                }
            }
            return true;
        }

        private void showItemMenu(LocalData.Item item) {
            boolean custom = userContent.isCustom(item.id);
            String[] actions = custom
                ? new String[]{"添加或修改笔记", "编辑内容", "分享", "删除自建内容"}
                : new String[]{"添加或修改笔记", "编辑内容", "分享"};
            new AlertDialog.Builder(getContext()).setTitle(item.title).setItems(actions, (dialog, which) -> {
                if (which == 0) showNoteDialog(item);
                else if (which == 1) showEditDialog(item, false);
                else if (which == 2) share(item);
                else deleteCustom(item);
            }).setNegativeButton("取消", null).show();
        }

        private void showItemDetails(LocalData.Item item, int category) {
            if (category == 5) {
                showTransportDetails(item);
                return;
            }
            LinearLayout content = new LinearLayout(getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) dp(22); content.setPadding(pad, dpInt(4), pad, dpInt(12));
            LinearLayout imageArea = new LinearLayout(getContext());
            imageArea.setOrientation(LinearLayout.VERTICAL);
            TextView loadingPhoto = addDetailText(imageArea, "正在加载实景图片…", 13, MUTED, false);
            content.addView(imageArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            addDetailText(content, item.subtitle, 15, INK, false);
            addDetailText(content, item.meta, 12, MUTED, false);
            String pickTitle = category == 0 ? "附近好店" : category == 4 ? "平价住宿参考"
                : category == 3 ? "近期避坑与出行提醒" : category == 1 ? "近期体验与分享" : "近期游玩攻略";
            addDetailText(content, pickTitle, 15, RED, true);
            LinearLayout picks = new LinearLayout(getContext());
            picks.setOrientation(LinearLayout.VERTICAL);
            addDetailText(picks, "正在从美团和小红书获取近期公开内容…", 13, MUTED, false);
            content.addView(picks, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView nearby = null;
            if (category == 2) {
                String locationNote = item.hasLocation()
                    ? "正在查询景点 5 公里内的周边信息…"
                    : "该条目暂无精确坐标，将以" + place.city + "市区为中心提供周边参考。";
                nearby = addDetailText(content, locationNote, 13, GREEN, false);
            }
            String note = userContent.note(item.id);
            if (!note.isEmpty()) {
                addDetailText(content, "我的笔记", 15, RED, true);
                addDetailText(content, note, 14, INK, false);
            }
            ScrollView scroll = new ScrollView(getContext()); scroll.addView(content);
            AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(item.title).setView(scroll)
                .setPositiveButton("更多操作", null).setNegativeButton("关闭", null).create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                dialog.dismiss(); showItemMenu(item);
            }));
            dialog.show();
            String cityCode = currentCityCode;
            String cityName = place.city;
            PhotoService.fetchItem(cityCode, cityName, item, category, photos -> post(() -> {
                if (!dialog.isShowing()) return;
                imageArea.removeAllViews();
                if (photos.isEmpty()) return;
                PhotoService.Photo photo = photos.get(0);
                ImageView image = new ImageView(getContext());
                image.setImageBitmap(photo.bitmap);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setContentDescription(item.title + "实景图片");
                image.setBackgroundColor(LINE);
                int imageHeight = Math.min(dpInt(230), Math.round(getWidth() * 0.58f));
                imageArea.addView(image, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, imageHeight));
                addDetailText(imageArea, "图片来源：" + photo.source, 11, MUTED, false);
            }));
            if (category == 0) {
                FoodShopService.fetch(currentCity, item.title, result -> post(() -> {
                    if (!dialog.isShowing()) return;
                    picks.removeAllViews();
                    if (result.shops.isEmpty()) {
                        addDetailText(picks, "暂未提取到可靠店铺名，可通过下方美团和小红书入口查看当前城市结果。", 13, INK, false);
                    } else {
                        int index = 1;
                        for (String shop : result.shops) addDetailText(picks,
                            index++ + ". " + shop + "\n" + result.sourceOf(shop)
                                + "公开结果 · 到店前核实", 13, INK, false);
                    }
                    addDetailText(picks, "美团公开结果优先，最多展示 5 家；不足时由小红书公开页面补充。评分与营业状态会变化，不构成商业排名。", 12, MUTED, false);
                    addSourceButton(picks, "查看美团公开检索结果", result.meituanUrl);
                    addSourceButton(picks, "在小红书搜索当地探店", result.xiaohongshuUrl);
                }));
            } else if (category == 4) {
                HotelRankService.fetch(currentCity, result -> post(() -> {
                    if (!dialog.isShowing()) return;
                    picks.removeAllViews();
                    if (result.hotels.isEmpty()) {
                        addDetailText(picks, "暂未提取到可靠住宿名，可通过下方美团和小红书入口查看。", 13, INK, false);
                    } else {
                        int index = 1;
                        for (String hotel : result.hotels) addDetailText(picks,
                            index++ + ". " + hotel + "\n" + result.sourceOf(hotel)
                                + "公开结果 · 平价住宿优先", 13, INK, false);
                    }
                    addDetailText(picks, "美团公开结果优先，最多展示 5 家；已过滤名称中明确标注三星及以上或高端品牌的住宿。房价与空房请以预订页为准。", 12, MUTED, false);
                    addSourceButton(picks, "查看美团住宿公开榜单", result.meituanUrl);
                    addSourceButton(picks, "在小红书核对住宿体验", result.xiaohongshuUrl);
                }));
            } else if (category == 3) {
                TravelTipService.fetchByItem(cityName, item.title, result -> post(() -> {
                    if (!dialog.isShowing()) return;
                    picks.removeAllViews();
                    if (result.summaries.isEmpty()) {
                        addDetailText(picks, "暂未提取到可核实的公开线索，可通过下方入口按出租车、购物、门票、收费和现场管理继续核对。", 13, MUTED, false);
                    } else {
                        int index = 1;
                        for (String summary : result.summaries) {
                            if (index > 3) break;
                            addDetailText(picks, index++ + ". " + summary, 13, INK, false);
                        }
                    }
                    addDetailText(picks, "公开笔记只作为风险线索，不代表问题目前仍存在；请结合文旅、市场监管、景区公告与现场信息交叉确认。", 12, MUTED, false);
                    addSourceButton(picks, "在美团搜索当地点评", result.meituanUrl);
                    addSourceButton(picks, "在小红书搜索当地避坑", result.xiaohongshuUrl);
                }));
            } else if (category == 1 || category == 2) {
                String keyword = category == 1 ? item.title + " 体验" : item.title + " 攻略";
                TravelTipService.fetchNotes(cityName, keyword, 3, result -> post(() -> {
                    if (!dialog.isShowing()) return;
                    picks.removeAllViews();
                    if (result.notes.isEmpty()) {
                        addDetailText(picks, "暂未提取到公开笔记，可通过下方入口继续搜索。", 13, MUTED, false);
                    } else {
                        int index = 1;
                        for (String line : result.notes) addDetailText(picks, index++ + ". " + line, 13, INK, false);
                    }
                    addDetailText(picks, "内容来自小红书公开页面，具有主观性和时效性，仅供参考。", 12, MUTED, false);
                    addSourceButton(picks, "在美团搜索相关去处", result.meituanUrl);
                    addSourceButton(picks, "在小红书查看完整笔记", result.xiaohongshuUrl);
                }));
            }
            if (category == 2) {
                double lat = item.hasLocation() ? item.lat : place.lat;
                double lon = item.hasLocation() ? item.lon : place.lon;
                TextView nearbyText = nearby;
                NearbyService.fetch(lat, lon, result -> post(() -> {
                    if (!dialog.isShowing()) return;
                    nearbyText.setText(buildNearbyText(result, item.hasLocation()));
                }));
            }
        }

        private String buildNearbyText(NearbyService.Result result, boolean exact) {
            String scope = exact ? "景点周边约 5 公里" : place.city + "市区周边参考";
            StringBuilder value = new StringBuilder("\n").append(scope).append("\n");
            appendNearbySection(value, "附近美食", result.food, localFoodFallback());
            appendNearbySection(value, "附近住宿", result.hotels, localHotelFallback());
            appendNearbySection(value, "交通汇聚点", result.transport,
                new String[]{"城市火车站或客运站 · 距离和班次请用地图复核", "公共交通换乘点 · 以实时导航为准"});
            value.append("\n距离为直线参考；营业、价格、空房、班次和步行路线请再次核实。");
            if (!result.fresh) value.append("\n当前网络查询未完成，已显示本地参考。");
            return value.toString();
        }

        private void showTransportDetails(LocalData.Item item) {
            LinearLayout content = new LinearLayout(getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = dpInt(22); content.setPadding(pad, dpInt(5), pad, dpInt(14));
            addDetailText(content, item.subtitle, 14, INK, false);
            addDetailText(content, item.meta, 12, MUTED, false);
            if (TransportService.isRailItem(item)) {
                CityRepository.City destination = ((MainActivity) getContext()).cityRepository
                    .findByCode(TransportService.destinationCode(item));
                if (destination == null) {
                    addDetailText(content, "暂时无法识别目的地，请返回后重新选择城市。", 13, RED, false);
                } else {
                    TextView schedule = addDetailText(content, "正在通过 12306 查询次日直达车次…", 13, GREEN, false);
                    LinearLayout actions = new LinearLayout(getContext());
                    actions.setOrientation(LinearLayout.VERTICAL); content.addView(actions);
                    CityRepository.City source = currentCity;
                    TransportService.fetchTrains(source, destination, result -> post(() -> {
                        StringBuilder value = new StringBuilder("查询日期：").append(result.date)
                            .append("\n").append(result.fromStation).append(" → ").append(result.toStation).append("\n");
                        if (!result.fresh) {
                            value.append("当前未能连接铁路实时查询，请在 12306 中按日期复核。\n");
                        } else if (result.trains.isEmpty()) {
                            value.append("次日未查询到直达列车，可在 12306 尝试换乘或更改日期。\n");
                        } else {
                            for (TransportService.Train train : result.trains) {
                                value.append("\n").append(train.code).append("  ")
                                    .append(train.departure).append(" → ").append(train.arrival)
                                    .append("  历时 ").append(train.duration);
                            }
                        }
                        value.append("\n\n车次、停运、余票和票价以 12306 下单页面为准。");
                        schedule.setText(value.toString());
                        actions.removeAllViews();
                        addSourceButton(actions, "打开铁路 12306 官方查询", result.officialUrl);
                        addSourceButton(actions, "在百度地图查看到站位置",
                            "https://map.baidu.com/search/" + Uri.encode(result.toStation));
                    }));
                }
            } else {
                String destination = TransportService.scenicName(item);
                addDetailText(content, "出发站：" + TransportService.mainStation(currentCity), 15, RED, true);
                addDetailText(content, "地图会按实时路况、运营时段和当前位置给出地铁、公交、步行及接驳方案。远郊景点请同时确认末班车和返程预约。", 13, MUTED, false);
                addSourceButton(content, "百度地图规划公共交通",
                    TransportService.baiduTransitUrl(currentCity, destination));
                addSourceButton(content, "高德地图核对路线",
                    TransportService.amapTransitUrl(currentCity, destination));
            }
            ScrollView scroll = new ScrollView(getContext()); scroll.addView(content);
            new AlertDialog.Builder(getContext()).setTitle(item.title).setView(scroll)
                .setPositiveButton("关闭", null).show();
        }

        private void appendNearbySection(StringBuilder value, String title, List<String> online, String[] fallback) {
            value.append("\n【").append(title).append("】\n");
            if (!online.isEmpty()) {
                for (String item : online) value.append("• ").append(item).append("\n");
            } else {
                for (String item : fallback) value.append("• ").append(item).append("\n");
            }
        }

        private String[] localFoodFallback() {
            List<LocalData.Item> food = userContent.apply(currentCityCode, 0, place.food);
            int size = Math.min(3, food.size()); String[] result = new String[size];
            for (int i = 0; i < size; i++) result[i] = food.get(i).title + " · 城市美食参考";
            return result;
        }

        private String[] localHotelFallback() {
            List<LocalData.Item> hotels = userContent.apply(currentCityCode, 4, place.hotels);
            int size = Math.min(3, hotels.size()); String[] result = new String[size];
            for (int i = 0; i < size; i++) result[i] = hotels.get(i).title + " · 价格空房需复核";
            return result;
        }

        private TextView addDetailText(LinearLayout parent, String value, int size, int color, boolean bold) {
            TextView text = new TextView(getContext()); text.setText(value); text.setTextSize(size);
            text.setTextColor(color); text.setLineSpacing(dp(3), 1f);
            text.setPadding(0, dpInt(7), 0, dpInt(5));
            if (bold) text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            parent.addView(text); return text;
        }

        private void addSourceButton(LinearLayout parent, String label, String url) {
            Button button = new Button(getContext());
            button.setText(label); button.setTextSize(13); button.setTextColor(RED);
            button.setAllCaps(false); button.setMinHeight(dpInt(46));
            button.setPadding(dpInt(18), 0, dpInt(18), 0);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xfffbe9e6);
            bg.setCornerRadius(dp(22));
            button.setBackground(bg);
            button.setStateListAnimator(null);
            button.setOnClickListener(v -> {
                try {
                    getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                    Toast.makeText(getContext(), "当前设备无法打开网页", Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpInt(46));
            params.topMargin = dpInt(6);
            parent.addView(button, params);
        }

        private int dpInt(float value) { return Math.round(dp(value)); }

        private void showNoteDialog(LocalData.Item item) {
            EditText note = editField("记录体验、地址、价格或提醒", userContent.note(item.id), 5);
            int pad = (int) dp(20);
            LinearLayout box = new LinearLayout(getContext());
            box.setPadding(pad, 0, pad, 0); box.addView(note);
            new AlertDialog.Builder(getContext()).setTitle("笔记 · " + item.title).setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    userContent.saveNote(currentCityCode, contentTab, item, note.getText().toString().trim());
                    Toast.makeText(getContext(), "笔记已保存", Toast.LENGTH_SHORT).show(); invalidate();
                }).setNegativeButton("取消", null).show();
        }

        private void showEditDialog(LocalData.Item item, boolean adding) {
            EditText title = editField("名称", item == null ? "" : item.title, 1);
            EditText subtitle = editField("说明", item == null ? "" : item.subtitle, 4);
            EditText meta = editField("标签或来源说明", item == null ? "我的添加" : item.meta, 2);
            LinearLayout box = new LinearLayout(getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) dp(20); box.setPadding(pad, 0, pad, 0);
            box.addView(title); box.addView(subtitle); box.addView(meta);
            AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(adding ? "新增" + categoryName(contentTab) : "编辑内容")
                .setView(box).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = title.getText().toString().trim();
                String description = subtitle.getText().toString().trim();
                String source = meta.getText().toString().trim();
                if (name.isEmpty() || description.isEmpty()) {
                    Toast.makeText(getContext(), "请填写名称和说明", Toast.LENGTH_SHORT).show(); return;
                }
                if (source.isEmpty()) source = adding ? "我的添加" : "本地修改";
                LocalData.Item saved = adding
                    ? userContent.add(currentCityCode, contentTab, name, description, source)
                    : userContent.update(currentCityCode, contentTab, item, name, description, source);
                if (favorites.contains(saved.id)) {
                    favoriteItems.put(saved.id, saved); persistFavoriteItems();
                }
                dialog.dismiss(); invalidate();
                Toast.makeText(getContext(), adding ? "已添加" : "修改已保存", Toast.LENGTH_SHORT).show();
            }));
            dialog.show();
        }

        private EditText editField(String hint, String value, int lines) {
            EditText field = new EditText(getContext());
            field.setHint(hint); field.setText(value); field.setTextSize(15); field.setMinLines(lines);
            field.setMaxLines(Math.max(lines, 5));
            return field;
        }

        private void share(LocalData.Item item) {
            String note = userContent.note(item.id);
            StringBuilder text = new StringBuilder(item.title).append("\n").append(place.city)
                .append("\n").append(item.subtitle).append("\n").append(item.meta);
            if (!note.isEmpty()) text.append("\n\n我的笔记：").append(note);
            text.append("\n\n来自乡遇");
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain"); intent.putExtra(Intent.EXTRA_SUBJECT, item.title);
            intent.putExtra(Intent.EXTRA_TEXT, text.toString());
            getContext().startActivity(Intent.createChooser(intent, "分享内容"));
        }

        private void deleteCustom(LocalData.Item item) {
            new AlertDialog.Builder(getContext()).setTitle("删除“" + item.title + "”？")
                .setMessage("删除后无法恢复，相关笔记也会一并移除。")
                .setPositiveButton("删除", (dialog, which) -> {
                    userContent.delete(item.id); favorites.remove(item.id); favoriteItems.remove(item.id);
                    prefs.edit().putStringSet("ids", new HashSet<>(favorites)).apply(); persistFavoriteItems();
                    invalidate(); Toast.makeText(getContext(), "已删除", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("取消", null).show();
        }

        private String categoryName(int category) {
            return new String[]{"小吃", "风俗", "景区", "避坑", "酒店", "交通"}[Math.max(0, Math.min(5, category))];
        }

        private String fit(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
        private String secondLine(String s, int n) { return s.length() <= n ? "" : s.substring(n); }

        private String fitWidth(String value, float textSize, float maxWidth) {
            paint.setTextSize(textSize);
            if (paint.measureText(value) <= maxWidth) return value;
            String suffix = "…";
            int end = value.length();
            while (end > 1 && paint.measureText(value.substring(0, end) + suffix) > maxWidth) end--;
            return value.substring(0, end) + suffix;
        }

        private String[] wrapTwoLines(String value, float textSize, float maxWidth) {
            paint.setTextSize(textSize);
            int split = value.length();
            while (split > 1 && paint.measureText(value.substring(0, split)) > maxWidth) split--;
            String first = value.substring(0, split);
            String rest = value.substring(split);
            return new String[]{first, rest.isEmpty() ? "" : fitWidth(rest, textSize, maxWidth)};
        }

        private void restoreFavoriteItems() {
            try {
                JSONArray array = new JSONArray(prefs.getString("item_snapshots", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject value = array.getJSONObject(i);
                    LocalData.Item item = new LocalData.Item(value.getString("id"), value.getString("title"),
                        value.getString("subtitle"), value.getString("meta"), value.getInt("color"), value.getString("mark"),
                        value.optDouble("lat", Double.NaN), value.optDouble("lon", Double.NaN));
                    favoriteItems.put(item.id, item);
                }
            } catch (Exception ignored) { }
        }

        private void persistFavoriteItems() {
            JSONArray array = new JSONArray();
            try {
                for (LocalData.Item item : favoriteItems.values()) {
                    if (!favorites.contains(item.id)) continue;
                    JSONObject value = new JSONObject();
                    value.put("id", item.id); value.put("title", item.title); value.put("subtitle", item.subtitle);
                    value.put("meta", item.meta); value.put("color", item.color); value.put("mark", item.mark);
                    if (item.hasLocation()) { value.put("lat", item.lat); value.put("lon", item.lon); }
                    array.put(value);
                }
                prefs.edit().putString("item_snapshots", array.toString()).apply();
            } catch (Exception ignored) { }
        }
        private float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
        private float dp(float value) { return value * density; }

        private void text(Canvas c, String s, float size, int color, float x, float y, boolean bold) {
            paint.setColor(color); paint.setTextSize(size); paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT); paint.setTextAlign(Paint.Align.LEFT); c.drawText(s, x, y, paint);
        }
        private void textCenter(Canvas c, String s, float size, int color, float x, float y, boolean bold) { paint.setTextAlign(Paint.Align.CENTER); paint.setColor(color); paint.setTextSize(size); paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT); c.drawText(s, x, y, paint); }
        private void textRight(Canvas c, String s, float size, int color, float x, float y, boolean bold) { paint.setTextAlign(Paint.Align.RIGHT); paint.setColor(color); paint.setTextSize(size); paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT); c.drawText(s, x, y, paint); }
        private void roundRect(Canvas c, float l, float t, float r, float b, float radius, int color) { paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawRoundRect(l, t, r, b, radius, radius, paint); }
        private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float width) { paint.setColor(color); paint.setStrokeWidth(dp(width)); c.drawLine(x1, y1, x2, y2, paint); }
        private void circle(Canvas c, float x, float y, float radius, int color) { paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawCircle(x, y, radius, paint); }
        private void pin(Canvas c, float x, float y, int color) { circle(c, x, y - dp(2), dp(4), color); paint.setColor(color); path.reset(); path.moveTo(x - dp(3), y); path.lineTo(x, y + dp(6)); path.lineTo(x + dp(3), y); path.close(); c.drawPath(path, paint); circle(c, x, y - dp(2), dp(1.5f), PAPER); }
        private void sun(Canvas c, float x, float y, int code) { circle(c, x, y, dp(18), 0xffffcf6a); if (code > 0) { circle(c, x + dp(12), y + dp(9), dp(15), 0xffecf0e9); circle(c, x - dp(2), y + dp(11), dp(11), 0xffecf0e9); roundRect(c, x - dp(14), y + dp(9), x + dp(28), y + dp(20), dp(6), 0xffecf0e9); } }
        private void heart(Canvas c, float x, float y, int color, boolean fill) { paint.setColor(color); paint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE); paint.setStrokeWidth(dp(1.7f)); path.reset(); path.moveTo(x, y + dp(8)); path.cubicTo(x - dp(15), y, x - dp(8), y - dp(10), x, y - dp(4)); path.cubicTo(x + dp(8), y - dp(10), x + dp(15), y, x, y + dp(8)); c.drawPath(path, paint); paint.setStyle(Paint.Style.FILL); }
        private void calendarIcon(Canvas c, float x, float y, int color) { paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.7f)); c.drawRoundRect(x - dp(9), y - dp(8), x + dp(9), y + dp(8), dp(2), dp(2), paint); c.drawLine(x - dp(9), y - dp(2), x + dp(9), y - dp(2), paint); c.drawCircle(x, y + dp(3), dp(1.6f), paint); paint.setStyle(Paint.Style.FILL); }
        private void compass(Canvas c, float x, float y, int color) { paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.7f)); c.drawCircle(x, y, dp(10), paint); path.reset(); path.moveTo(x + dp(5), y - dp(6)); path.lineTo(x + dp(1), y + dp(3)); path.lineTo(x - dp(5), y + dp(6)); path.lineTo(x - dp(1), y - dp(3)); path.close(); c.drawPath(path, paint); paint.setStyle(Paint.Style.FILL); }
        private void bookmark(Canvas c, float x, float y, int color, boolean fill) { paint.setColor(color); paint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE); paint.setStrokeWidth(dp(1.7f)); path.reset(); path.moveTo(x - dp(7), y - dp(10)); path.lineTo(x + dp(7), y - dp(10)); path.lineTo(x + dp(7), y + dp(10)); path.lineTo(x, y + dp(5)); path.lineTo(x - dp(7), y + dp(10)); path.close(); c.drawPath(path, paint); paint.setStyle(Paint.Style.FILL); }
    }
}
