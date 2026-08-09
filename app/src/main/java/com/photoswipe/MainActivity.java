package com.photoswipe;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
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
import com.google.android.material.snackbar.Snackbar;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private ImageView imageView;
    private VideoView videoView;
    private View videoContainer;
    private TextView tvCounter, tvType, tvMonth, tvStats;
    private View tvEmpty;
    private View overlayKeep, overlayDelete;
    private CardView cardView;
    private View btnFolder, btnExit, btnTrash;
    private TextView tvTrashCount;

    private List<String> mediaPaths = new ArrayList<>();
    private int currentIndex = 0;
    private String currentFolderPath = "";
    private boolean positionSaved = false;
    private SharedPreferences prefs;

    private float startX;
    private int screenWidth;
    private boolean isSwiping = false;

    // Undo support
    private String lastTrashedPath = null;

    private static final int PERMISSION_REQUEST = 100;
    private static final int FOLDER_PICK_REQUEST = 200;
    private static final String PREF_INDEX   = "saved_index";
    private static final String PREF_FOLDER  = "saved_folder";
    private static final String PREF_KEPT    = "kept_count";
    private static final String PREF_DELETED = "deleted_count";
    private static final String PREF_BYTES   = "bytes_freed";
    static final String PREF_TRASH   = "trash_paths";  // pipe-separated paths

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("photoswipe", MODE_PRIVATE);
        screenWidth = getResources().getDisplayMetrics().widthPixels;

        imageView      = findViewById(R.id.imageView);
        videoView      = findViewById(R.id.videoView);
        videoContainer = findViewById(R.id.videoContainer);
        tvCounter      = findViewById(R.id.tvCounter);
        tvType         = findViewById(R.id.tvType);
        tvMonth        = findViewById(R.id.tvMonth);
        tvStats        = findViewById(R.id.tvStats);
        tvEmpty        = findViewById(R.id.tvEmpty);
        overlayKeep    = findViewById(R.id.overlayKeep);
        overlayDelete  = findViewById(R.id.overlayDelete);
        cardView       = findViewById(R.id.cardView);
        btnFolder      = findViewById(R.id.btnFolder);
        btnExit        = findViewById(R.id.btnExit);
        btnTrash       = findViewById(R.id.btnTrash);
        tvTrashCount   = findViewById(R.id.tvTrashCount);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(videoView);
        videoView.setMediaController(mc);

        cardView.setOnTouchListener(this::onCardTouch);
        btnFolder.setOnClickListener(v -> requestPermissionsAndPick());
        btnExit.setOnClickListener(v -> onExitPressed());
        btnTrash.setOnClickListener(v -> startActivity(new Intent(this, TrashActivity.class)));

        updateStatsBar();
        updateTrashBadge();

        new android.os.Handler().postDelayed(this::checkSavedSession, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTrashBadge();
    }

    // ── SWIPE TOUCH ──────────────────────────────────

    private boolean onCardTouch(View v, MotionEvent event) {
        if (mediaPaths.isEmpty()) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getRawX();
                isSwiping = true;
                cardView.animate().cancel();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!isSwiping) return true;
                float dx = event.getRawX() - startX;
                cardView.setTranslationX(dx);
                cardView.setRotation(dx / 20f);
                if (dx > 0) {
                    overlayKeep.setVisibility(View.VISIBLE);
                    overlayKeep.setAlpha(Math.min(dx / 300f, 0.9f));
                    overlayDelete.setVisibility(View.GONE);
                } else {
                    overlayDelete.setVisibility(View.VISIBLE);
                    overlayDelete.setAlpha(Math.min(-dx / 300f, 0.9f));
                    overlayKeep.setVisibility(View.GONE);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isSwiping) return true;
                isSwiping = false;
                float finalDx = event.getRawX() - startX;
                if (finalDx > screenWidth / 5f)       animateCardOut(1);
                else if (finalDx < -screenWidth / 5f) animateCardOut(-1);
                else springBack();
                return true;
        }
        return false;
    }

    private void animateCardOut(int dir) {
        cardView.animate()
                .translationX(dir * screenWidth)
                .rotation(dir * 30f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction(() -> {
                    if (dir > 0) processKeep(); else processTrash();
                    resetCard();
                    showCurrent();
                }).start();
    }

    private void springBack() {
        cardView.animate().translationX(0).rotation(0).alpha(1f).setDuration(200).start();
        overlayKeep.setVisibility(View.GONE);
        overlayDelete.setVisibility(View.GONE);
    }

    private void resetCard() {
        cardView.setTranslationX(0);
        cardView.setRotation(0);
        cardView.setAlpha(1f);
        overlayKeep.setVisibility(View.GONE);
        overlayDelete.setVisibility(View.GONE);
    }

    // ── KEEP / TRASH ──────────────────────────────────

    private void processKeep() {
        if (mediaPaths.isEmpty()) return;
        positionSaved = false;
        prefs.edit().putInt(PREF_KEPT, prefs.getInt(PREF_KEPT, 0) + 1).apply();
        currentIndex++;
        updateStatsBar();
    }

    private void processTrash() {
        if (mediaPaths.isEmpty()) return;
        positionSaved = false;
        String path = mediaPaths.get(currentIndex);
        long fileSize = new File(path).length();

        // Add to virtual trash list in SharedPreferences
        addToTrashList(path);
        lastTrashedPath = path;

        mediaPaths.remove(currentIndex);

        int deleted = prefs.getInt(PREF_DELETED, 0) + 1;
        long bytes  = prefs.getLong(PREF_BYTES, 0L) + fileSize;
        prefs.edit().putInt(PREF_DELETED, deleted).putLong(PREF_BYTES, bytes).apply();

        updateStatsBar();
        updateTrashBadge();

        Snackbar.make(cardView, "Movido a papelera", Snackbar.LENGTH_LONG)
                .setDuration(5000)
                .setAction("Deshacer", v -> undoTrash())
                .setActionTextColor(0xFF00C853)
                .setBackgroundTint(0xFF222222)
                .setTextColor(0xFFFFFFFF)
                .show();
    }

    // ── VIRTUAL TRASH (SharedPreferences) ────────────

    private void addToTrashList(String path) {
        String existing = prefs.getString(PREF_TRASH, "");
        String updated = existing.isEmpty() ? path : existing + "|" + path;
        prefs.edit().putString(PREF_TRASH, updated).apply();
    }

    private void undoTrash() {
        if (lastTrashedPath == null) return;
        // Remove from trash list
        String existing = prefs.getString(PREF_TRASH, "");
        List<String> list = new ArrayList<>(Arrays.asList(existing.split("\\|")));
        list.remove(lastTrashedPath);
        prefs.edit().putString(PREF_TRASH, android.text.TextUtils.join("|", list)).apply();

        // Re-insert into current view
        mediaPaths.add(currentIndex, lastTrashedPath);
        lastTrashedPath = null;

        int deleted = Math.max(0, prefs.getInt(PREF_DELETED, 0) - 1);
        prefs.edit().putInt(PREF_DELETED, deleted).apply();

        updateStatsBar();
        updateTrashBadge();
        showCurrent();
        Toast.makeText(this, "Restaurado", Toast.LENGTH_SHORT).show();
    }

    private void updateTrashBadge() {
        String trash = prefs.getString(PREF_TRASH, "");
        int count = trash.isEmpty() ? 0 : trash.split("\\|").length;
        if (tvTrashCount != null) tvTrashCount.setText("🗑 " + count);
    }

    // ── DISPLAY ───────────────────────────────────────

    private void showCurrent() {
        overlayKeep.setVisibility(View.GONE);
        overlayDelete.setVisibility(View.GONE);
        videoView.stopPlayback();

        if (mediaPaths.isEmpty() || currentIndex >= mediaPaths.size()) {
            tvCounter.setText("¡Todo revisado!");
            cardView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            prefs.edit().remove(PREF_INDEX).remove(PREF_FOLDER).apply();
            tvMonth.setText("");
            return;
        }

        cardView.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        String path = mediaPaths.get(currentIndex);
        tvCounter.setText((currentIndex + 1) + " / " + mediaPaths.size());
        updateMonthLabel(path);

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

    private void updateMonthLabel(String path) {
        try {
            long modified = new File(path).lastModified();
            SimpleDateFormat sdf = new SimpleDateFormat("MMMM yyyy", new Locale("es"));
            String label = sdf.format(new Date(modified));
            if (!label.isEmpty()) label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
            tvMonth.setText(label);
        } catch (Exception e) { tvMonth.setText(""); }
    }

    private void updateStatsBar() {
        int kept    = prefs.getInt(PREF_KEPT, 0);
        int deleted = prefs.getInt(PREF_DELETED, 0);
        long bytes  = prefs.getLong(PREF_BYTES, 0L);
        double mb   = bytes / 1024.0 / 1024.0;
        tvStats.setText("✓ " + kept + "   🗑 " + deleted + "   💾 " + String.format(Locale.getDefault(), "%.1f MB", mb));
    }

    // ── FOLDER LOADING ────────────────────────────────

    private void loadMediaFromPath(String folderPath, int startIndex) {
        mediaPaths.clear();
        currentIndex = 0;
        currentFolderPath = folderPath;
        positionSaved = false;
        scanFolder(new File(folderPath));
        mediaPaths.sort((a, b) -> Long.compare(new File(b).lastModified(), new File(a).lastModified()));
        if (mediaPaths.isEmpty()) {
            Toast.makeText(this, "No hay fotos/videos en esa carpeta", Toast.LENGTH_SHORT).show();
            tvEmpty.setVisibility(View.VISIBLE);
            cardView.setVisibility(View.GONE);
        } else {
            currentIndex = Math.min(startIndex, mediaPaths.size() - 1);
            tvEmpty.setVisibility(View.GONE);
            cardView.setVisibility(View.VISIBLE);
            showCurrent();
        }
    }

    private void scanFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                n.endsWith(".webp") || n.endsWith(".heic") || n.endsWith(".mp4") ||
                n.endsWith(".mkv") || n.endsWith(".3gp") || n.endsWith(".mov") ||
                n.endsWith(".avi") || n.endsWith(".webm"))
                mediaPaths.add(f.getAbsolutePath());
        }
    }

    // ── PERMISSIONS & FOLDER PICKER ───────────────────

    private void requestPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] perms = {Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
            boolean granted = true;
            for (String p : perms)
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
            if (!granted) { ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST); return; }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST);
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
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) openFolderPicker();
            else Toast.makeText(this, "Se necesitan permisos", Toast.LENGTH_LONG).show();
        }
    }

    // ── CONTINUE SESSION ──────────────────────────────

    private void checkSavedSession() {
        String savedFolder = prefs.getString(PREF_FOLDER, "");
        int savedIndex = prefs.getInt(PREF_INDEX, 0);
        if (savedFolder.isEmpty() || savedIndex <= 0) return;

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_continue);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.88),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setCancelable(false);

        ((TextView) dialog.findViewById(R.id.tvDialogInfo)).setText("Quedaste en la foto " + (savedIndex + 1));
        ((TextView) dialog.findViewById(R.id.tvDialogFolder)).setText(savedFolder);

        dialog.findViewById(R.id.btnContinue).setOnClickListener(v -> { dialog.dismiss(); loadMediaFromPath(savedFolder, savedIndex); });
        dialog.findViewById(R.id.btnNewFolder).setOnClickListener(v -> { dialog.dismiss(); prefs.edit().remove(PREF_FOLDER).remove(PREF_INDEX).apply(); });
        dialog.show();
    }

    // ── EXIT DIALOG ───────────────────────────────────

    private void onExitPressed() {
        if (mediaPaths.isEmpty()) { finish(); return; }
        if (positionSaved) { finish(); return; }

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_exit);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.88),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setCancelable(true);

        ((TextView) dialog.findViewById(R.id.tvExitInfo)).setText("Vas en la foto " + (currentIndex + 1) + " de " + mediaPaths.size());
        dialog.findViewById(R.id.btnExitSave).setOnClickListener(v -> { savePosition(); dialog.dismiss(); finish(); });
        dialog.findViewById(R.id.btnExitNoSave).setOnClickListener(v -> { dialog.dismiss(); finish(); });
        dialog.show();
    }

    private void savePosition() {
        prefs.edit().putString(PREF_FOLDER, currentFolderPath).putInt(PREF_INDEX, currentIndex).apply();
        positionSaved = true;
    }

    // ── UTILS ─────────────────────────────────────────

    private boolean isVideo(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".3gp")
                || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".webm");
    }

    @Override
    public void onBackPressed() { onExitPressed(); }

    @Override
    protected void onPause() { super.onPause(); if (videoView != null) videoView.pause(); }
}
