package com.mr.flutter.plugin.filepicker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/**
 * FilePickerPlugin
 */
public class FilePickerPlugin implements MethodCallHandler, PluginRegistry.ActivityResultListener, PluginRegistry.RequestPermissionsResultListener {

    private static final int REQUEST_CODE = (FilePickerPlugin.class.hashCode() + 43) & 0x0000ffff;
    private static final int PERM_CODE = (FilePickerPlugin.class.hashCode() + 50) & 0x0000ffff;
    private static final String TAG = "FilePicker";
    private static final String permission = Manifest.permission.READ_EXTERNAL_STORAGE;

    private final LooperThread thread;

    private FilePickerPlugin(Registrar registrar) {
        this.registrar = registrar;
        this.thread = new LooperThread();
        thread.start();
    }

    private final Registrar registrar;

    private Result result;
    private String fileType;
    private boolean isMultipleSelection = false;

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        if (registrar.activity() == null) {
            // If a background flutter view tries to register the plugin, there will be no activity from the registrar,
            // we stop the registering process immediately because the ImagePicker requires an activity.
            return;
        }

        FilePickerPlugin handler = new FilePickerPlugin(registrar);

        final MethodChannel channel = new MethodChannel(registrar.messenger(), "file_picker");
        channel.setMethodCallHandler(handler);

        registrar.addActivityResultListener(handler);
        registrar.addRequestPermissionsResultListener(handler);
    }

    private void runOnUiThread(final Object o, final boolean success) {
        registrar.activity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (success) {
                    result.success(o);
                } else if (o != null) {
                    result.error(TAG, (String) o, null);
                } else {
                    result.notImplemented();
                }
                result = null;
            }
        });
    }

    @Override
    public void onMethodCall(MethodCall call, @NonNull Result result) {
        this.result = result;
        fileType = resolveType(call.method);
        isMultipleSelection = (boolean) call.arguments;

        if (fileType == null) {
            result.notImplemented();
        } else if (fileType.equals("unsupported")) {
            result.error(TAG, "Unsupported filter. Make sure that you are only using the extension without the dot, (ie., jpg instead of .jpg). This could also have happened because you are using an unsupported file extension.  If the problem persists, you may want to consider using FileType.ALL instead.", null);
        } else {
            startFileExplorer(fileType);
        }

    }

    private boolean checkPermission() {
        Activity activity = registrar.activity();
        Log.i(TAG, "Checking permission: " + permission);
        return PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(activity, permission);
    }

    private void requestPermission() {
        Activity activity = registrar.activity();
        Log.i(TAG, "Requesting permission: " + permission);
        String[] perm = {permission};
        ActivityCompat.requestPermissions(activity, perm, PERM_CODE);
    }

    private String resolveType(String type) {

        final boolean isCustom = type.contains("__CUSTOM_");

        if (isCustom) {
            final String extension = type.split("__CUSTOM_")[1].toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            mime = mime == null ? "unsupported" : mime;
            Log.i(TAG, "Custom file type: " + mime);
            return mime;
        }

        switch (type) {
            case "AUDIO":
                return "audio/*";
            case "IMAGE":
                return "image/*";
            case "VIDEO":
                return "video/*";
            case "ANY":
                return "*/*";
            default:
                return null;
        }
    }


    private void startFileExplorer(String type) {
        Intent intent;

        if (checkPermission()) {

            intent = new Intent(Intent.ACTION_GET_CONTENT);
            Uri uri = Uri.parse(Environment.getExternalStorageDirectory().getPath() + File.separator);
            intent.setDataAndType(uri, type);
            intent.setType(type);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultipleSelection);
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            if (intent.resolveActivity(registrar.activity().getPackageManager()) != null) {
                registrar.activity().startActivityForResult(intent, REQUEST_CODE);
            } else {
                Log.e(TAG, "Can't find a valid activity to handle the request. Make sure you've a file explorer installed.");
                result.error(TAG, "Can't handle the provided file type.", null);
            }
        } else {
            requestPermission();
        }
    }

    private static class ActivityResultHandler extends Handler {
        private final FilePickerPlugin plugin;

        ActivityResultHandler(FilePickerPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void handleMessage(Message msg) {
            Result result = plugin.result;
            Registrar registrar = plugin.registrar;

            Intent data = (Intent) msg.obj;

            if (data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    int currentItem = 0;
                    ArrayList<String> paths = new ArrayList<>();
                    while (currentItem < count) {
                        final Uri currentUri = data.getClipData().getItemAt(currentItem).getUri();
                        String path = FileUtils.getPath(currentUri, registrar.context());
                        if (path == null) {
                            path = FileUtils.getUriFromRemote(registrar.activeContext(), currentUri, result);
                        }
                        paths.add(path);
                        Log.i(TAG, "[MultiFilePick] File #" + currentItem + " - URI: " + currentUri.getPath());
                        currentItem++;
                    }
                    if (paths.size() > 1) {
                        plugin.runOnUiThread(paths, true);
                    } else {
                        plugin.runOnUiThread(paths.get(0), true);
                    }
                } else if (data.getData() != null) {
                    Uri uri = data.getData();
                    Log.i(TAG, "[SingleFilePick] File URI:" + uri.toString());
                    String fullPath = FileUtils.getPath(uri, registrar.context());

                    if (fullPath == null) {
                        fullPath = FileUtils.getUriFromRemote(registrar.activeContext(), uri, result);
                    }

                    if (fullPath != null) {
                        Log.i(TAG, "Absolute file path:" + fullPath);
                        plugin.runOnUiThread(fullPath, true);
                    } else {
                        plugin.runOnUiThread("Failed to retrieve path.", false);
                    }
                } else {
                    plugin.runOnUiThread("Unknown activity error, please fill an issue.", false);
                }
            } else {
                plugin.runOnUiThread("Unknown activity error, please fill an issue.", false);
            }
        }
    }

    class LooperThread extends Thread {
        private Handler mHandler;

        @Override
        public void run() {
            Looper.prepare();
            mHandler = new ActivityResultHandler(FilePickerPlugin.this);
            Looper.loop();
        }

        void send(Intent data) {
            mHandler.sendMessage(Message.obtain(mHandler, 0, data));
        }
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (result != null) {
                thread.send(data);
            }
            return true;
        } else if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_CANCELED) {
            result.success(null);
            result = null;
            return true;
        } else if (requestCode == REQUEST_CODE) {
            result.error(TAG, "Unknown activity error, please fill an issue.", null);
            result = null;
        }
        return false;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] strings, int[] grantResults) {
        if (requestCode == PERM_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startFileExplorer(fileType);
            return true;
        }
        return false;
    }

}
