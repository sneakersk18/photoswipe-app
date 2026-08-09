package com.photoswipe;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.bumptech.glide.Glide;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ImageView imageView;
    private VideoView videoView;
    private View videoContainer;
    private TextView tvCounter, tvType;
    private View tvEmpty;
    private View overlayKeep, overlayDelete;
    private CardView cardView;
    private View btnFolder, btnDelete, btnKeep, btnSave;
    private GestureDetector gestureDetector;
    private List<String> mediaPaths = new ArrayList<>();
    private int currentIndex = 0;
    private String currentFolderPath = "";
    private SharedPreferences prefs;

    private static final int PERMISSION_REQUEST = 100;
    private static final int FOLDER_PICK_REQUEST = 200;
    private static final int DELETE_REQUEST = 300;
    private static final String PREF_INDEX = "saved_index";
    private static final String PREF_FOLDER = "saved_folder";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("photoswipe", MODE_PRIVATE);

        imageView      = findViewById(R.id.imageView);
        videoView      = findViewById(R.id.videoView);
        videoContainer = findViewById(R.id.videoContainer);
        tvCounter    = findViewById(R.id.tvCounter);
        tvEmpty      = findViewById(R.id.tvEmpty);
        tvType       = findViewById(R.id.tvType);
        overlayKeep  = findViewById(R.id.overlayKeep);
        overlayDelete= findViewById(R.id.overlayDelete);
        cardView     = findViewById(R.id.cardView);
        btnFolder    = findViewById(R.id.btnFolder);
        btnDelete    = findViewById(R.id.btnDelete);
        btnKeep      = findViewById(R.id.btnKeep);
        btnSave      = findViewById(R.id.btnSave);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(videoView);
        videoView.setMediaController(mc);

        gestureDetector = new GestureDetector(this, new SwipeListener());
        cardView.setOnTouchListener((v, event) -> { gestureDetector.onTouchEvent(event); return true; });

        btnFolder.setOnClickListener(v -> requestPermissionsAndPick());
        btnDelete.setOnClickListener(v -> { if (!mediaPaths.isEmpty()) deleteFile(); });
        btnKeep.setOnClickListener(v -> { if (!mediaPaths.isEmpty()) keepFile(); });
        btnSave.setOnClickListener(v -> savePosition());

        new android.os.Handler().postDelayed(this::checkSavedSession, 300);
    }

    private void checkSavedSession() {
        String savedFolder = prefs.getString(PREF_FOLDER, "");
        int savedIndex = prefs.getInt(PREF_INDEX, 0);
        if (savedFolder.isEmpty() || savedIndex <= 0) return;

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_continue);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.88),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setCancelable(false);

        TextView tvInfo = dialog.findViewById(R.id.tvDialogInfo);
        TextView tvFolder = dialog.findViewById(R.id.tvDialogFolder);
        tvInfo.setText("Quedaste en la foto " + (savedIndex + 1));
        tvFolder.setText(savedFolder);

        dialog.findViewById(R.id.btnContinue).setOnClickListener(v -> {
            dialog.dismiss();
            loadMediaFromPath(savedFolder, savedIndex);
        });
        dialog.findViewById(R.id.btnNewFolder).setOnClickListener(v -> {
            dialog.dismiss();
            prefs.edit().clear().apply();
        });
        dialog.show();
    }

    private void savePosition() {
        if (mediaPaths.isEmpty() || currentFolderPath.isEmpty()) {
            Toast.makeText(this, "Primero selecciona una carpeta", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit()
            .putString(PREF_FOLDER, currentFolderPath)
            .putInt(PREF_INDEX, currentIndex)
            .apply();
        Toast.makeText(this, "✓ Posición guardada (foto " + (currentIndex + 1) + ")", Toast.LENGTH_SHORT).show();
    }

    private boolean isVideo(String path) {
        String n = path.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".3gp")
                || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".webm");
    }

    private void requestPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] perms = {Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
            boolean granted = true;
            for (String p : perms)
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
            if (!granted) { ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST); return; }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
                return;
            }
        }
        openFolderPicker();
    }

    private void openFolderPicker() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), FOLDER_PICK_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FOLDER_PICK_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            String[] split = docId.split(":");
            String type = split[0];
            String path = split.length > 1 ? split[1] : "";
            String fullPath = "primary".equalsIgnoreCase(type)
                    ? Environment.getExternalStorageDirectory() + "/" + path
                    : "/storage/" + type + "/" + path;
            loadMediaFromPath(fullPath, 0);
        }
        if (requestCode == DELETE_REQUEST && resultCode == Activity.RESULT_OK) {
            mediaPaths.remove(currentIndex);
            showCurrent();
        }
    }

    private void loadMediaFromPath(String folderPath, int startIndex) {
        mediaPaths.clear();
        currentIndex = 0;
        currentFolderPath = folderPath;
        scanFolder(new File(folderPath));
        if (mediaPaths.isEmpty()) {
            Toast.makeText(this, "No hay fotos/videos en esa carpeta", Toast.LENGTH_SHORT).show();
            tvEmpty.setVisibility(View.VISIBLE);
            cardView.setVisibility(View.GONE);
            btnSave.setVisibility(View.GONE);
        } else {
            currentIndex = Math.min(startIndex, mediaPaths.size() - 1);
            tvEmpty.setVisibility(View.GONE);
            cardView.setVisibility(View.VISIBLE);
            btnSave.setVisibility(View.VISIBLE);
            showCurrent();
        }
    }

    private void scanFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                n.endsWith(".webp") || n.endsWith(".heic") || n.endsWith(".mp4") ||
                n.endsWith(".mkv") || n.endsWith(".3gp") || n.endsWith(".mov") ||
                n.endsWith(".avi") || n.endsWith(".webm"))
                mediaPaths.add(f.getAbsolutePath());
        }
    }

    private void showCurrent() {
        overlayKeep.setVisibility(View.GONE);
        overlayDelete.setVisibility(View.GONE);
        videoView.stopPlayback();

        if (mediaPaths.isEmpty() || currentIndex >= mediaPaths.size()) {
            tvCounter.setText("¡Todo revisado!");
            cardView.setVisibility(View.GONE);
            btnSave.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            // sesión terminada
            prefs.edit().clear().apply();
            return;
        }

        String path = mediaPaths.get(currentIndex);
        tvCounter.setText((currentIndex + 1) + " / " + mediaPaths.size());

        if (isVideo(path)) {
            tvType.setText("🎬 VIDEO");
            tvType.setBackgroundResource(R.drawable.badge_video);
            imageView.setVisibility(View.GONE);
            videoContainer.setVisibility(View.VISIBLE);
            videoView.setVideoURI(Uri.fromFile(new File(path)));
            videoView.start();
        } else {
            tvType.setText("📷 FOTO");
            tvType.setBackgroundResource(R.drawable.badge_photo);
            videoContainer.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            Glide.with(this).load(new File(path)).into(imageView);
        }
    }

    private void keepFile() {
        overlayKeep.setVisibility(View.VISIBLE);
        cardView.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_out));
        cardView.postDelayed(() -> { currentIndex++; showCurrent(); }, 200);
    }

    private void deleteFile() {
        String path = mediaPaths.get(currentIndex);
        overlayDelete.setVisibility(View.VISIBLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Uri uri = getMediaUri(path);
            if (uri != null) {
                try {
                    List<Uri> uris = new ArrayList<>();
                    uris.add(uri);
                    PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                    startIntentSenderForResult(pi.getIntentSender(), DELETE_REQUEST, null, 0, 0, 0);
                    return;
                } catch (IntentSender.SendIntentException e) { e.printStackTrace(); }
            }
        }

        File file = new File(path);
        if (file.delete()) {
            try { getContentResolver().delete(MediaStore.Files.getContentUri("external"), MediaStore.MediaColumns.DATA + "=?", new String[]{path}); } catch (Exception ignored) {}
            mediaPaths.remove(currentIndex);
            showCurrent();
        } else {
            Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show();
            overlayDelete.setVisibility(View.GONE);
            currentIndex++;
            showCurrent();
        }
    }

    private Uri getMediaUri(String path) {
        String[] proj = {MediaStore.MediaColumns._ID};
        String sel = MediaStore.MediaColumns.DATA + "=?";
        Uri[] cols = {MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI};
        for (Uri col : cols) {
            try (android.database.Cursor c = getContentResolver().query(col, proj, sel, new String[]{path}, null)) {
                if (c != null && c.moveToFirst())
                    return Uri.withAppendedPath(col, String.valueOf(c.getLong(0)));
            }
        }
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) openFolderPicker();
            else Toast.makeText(this, "Se necesitan permisos de almacenamiento", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() { super.onPause(); videoView.pause(); }

    private class SwipeListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
            if (mediaPaths.isEmpty()) return false;
            float diffX = e2.getX() - e1.getX();
            if (Math.abs(diffX) > Math.abs(e2.getY() - e1.getY()) && Math.abs(diffX) > 100 && Math.abs(vX) > 100) {
                if (diffX > 0) keepFile(); else deleteFile();
                return true;
            }
            return false;
        }
    }
}
