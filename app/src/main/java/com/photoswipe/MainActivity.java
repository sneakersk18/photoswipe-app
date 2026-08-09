package com.photoswipe;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
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
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // ───── Views ─────
    private ImageView imageView;
    private VideoView videoView;
    private View videoContainer;
    private TextView tvCounter, tvType, tvMonth, tvStats;
    private View tvEmpty;
    private View overlayKeep, overlayDelete;
    private CardView cardView;
    private View btnFolder, btnExit, btnTrash;
    private TextView tvTrashCount;

    // ───── State ─────
    private List<String> mediaPaths = new ArrayList<>();
    private int currentIndex = 0;
    private String currentFolderPath = "";
    private boolean positionSaved = false;
    private SharedPreferences prefs;

    // ───── Swipe tracking ─────
    private float startX, startY;
    private int screenWidth;
    private boolean isSwiping = false;

    // ───── Trash / Undo ─────
    private File trashDir;
    private File lastTrashedFile;
    private String lastOriginalPath;

    // ───── Constants ─────
    private static final int PERMISSION_REQUEST = 100;
    private static final int FOLDER_PICK_REQUEST = 200;
    private static final String PREF_INDEX   = "saved_index";
    private static final String PREF_FOLDER  = "saved_folder";
    private static final String PREF_KEPT    = "kept_count";
    private static final String PREF_DELETED = "deleted_count";
    private static final String PREF_BYTES   = "bytes_freed";

    // ───────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("photoswipe", MODE_PRIVATE);
        screenWidth = getResources().getDisplayMetrics().widthPixels;

        trashDir = new File(getFilesDir(), "trash");
        trashDir.mkdirs();

        // View references
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

        // MediaController for video
        MediaController mc = new MediaController(this);
        mc.setAnchorView(videoView);
        videoView.setMediaController(mc);

        // Touch listener for live swipe animation
        cardView.setOnTouchListener(this::onCardTouch);

        btnFolder.setOnClickListener(v -> requestPermissionsAndPick());
        btnExit.setOnClickListener(v -> onExitPressed());
        btnTrash.setOnClickListener(v -> openTrash());

        updateStatsBar();
        updateTrashBadge();

        new android.os.Handler().postDelayed(this::checkSavedSession, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTrashBadge();
    }

    // ═══════════════════════════════════════════════
    //  SWIPE TOUCH HANDLER
    // ═══════════════════════════════════════════════

    private boolean onCardTouch(View v, MotionEvent event) {
        if (mediaPaths.isEmpty()) return false;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getRawX();
                startY = event.getRawY();
                isSwiping = true;
                // Stop any ongoing animator
                cardView.animate().cancel();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!isSwiping) return true;
                float dx = event.getRawX() - startX;
                float dy = event.getRawY() - startY;

                // Only swipe if horizontal motion dominates
                if (Math.abs(dx) < Math.abs(dy) * 0.5f && Math.abs(dx) < 20) {
                    return true;
                }

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
                float threshold = screenWidth / 5f;

                if (finalDx > threshold) {
                    animateCardOut(1);         // swipe right = keep
                } else if (finalDx < -threshold) {
                    animateCardOut(-1);        // swipe left = delete/trash
                } else {
                    springBack();
                }
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
                    if (dir > 0) {
                        processKeep();
                    } else {
                        processTrash();
                    }
                    resetCard();
                    showCurrent();
                })
                .start();
    }

    private void springBack() {
        cardView.animate()
                .translationX(0f)
                .rotation(0f)
                .alpha(1f)
                .setDuration(200)
                .withEndAction(() -> {
                    overlayKeep.setVisibility(View.GONE);
                    overlayDelete.setVisibility(View.GONE);
                })
                .start();
    }

    private void resetCard() {
        cardView.setTranslationX(0f);
        cardView.setRotation(0f);
        cardView.setAlpha(1f);
        overlayKeep.setVisibility(View.GONE);
        overlayDelete.setVisibility(View.GONE);
    }

    // ═══════════════════════════════════════════════
    //  KEEP / TRASH LOGIC
    // ═══════════════════════════════════════════════

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
        File file = new File(path);

        long fileSize = file.length();
        moveToTrash(path);

        // Update statistics
        int deleted = prefs.getInt(PREF_DELETED, 0) + 1;
        long bytes  = prefs.getLong(PREF_BYTES, 0L) + fileSize;
        prefs.edit()
                .putInt(PREF_DELETED, deleted)
                .putLong(PREF_BYTES, bytes)
                .apply();

        mediaPaths.remove(currentIndex);
        // Don't advance index — next item slides into position
        updateStatsBar();
        updateTrashBadge();

        // Undo snackbar
        Snackbar.make(cardView, "Movido a papelera", Snackbar.LENGTH_LONG)
                .setDuration(5000)
                .setAction("Deshacer", v -> undoTrash())
                .setActionTextColor(0xFF00C853)
                .setBackgroundTint(0xFF222222)
                .setTextColor(0xFFFFFFFF)
                .show();
    }

    // ═══════════════════════════════════════════════
    //  TRASH OPERATIONS
    // ═══════════════════════════════════════════════

    private void moveToTrash(String originalPath) {
        File src = new File(originalPath);
        if (!src.exists()) return;

        File dest = new File(trashDir, src.getName());
        // Handle name collisions in trash
        if (dest.exists()) {
            dest = new File(trashDir, System.currentTimeMillis() + "_" + src.getName());
        }

        boolean moved = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                java.nio.file.Files.move(src.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (!moved) {
            moved = copyFileLegacy(src, dest);
            if (moved) src.delete();
        }

        if (moved) {
            lastTrashedFile  = dest;
            lastOriginalPath = originalPath;

            // Write sidecar with original path
            writeSidecar(dest, originalPath);

            // Remove from MediaStore
            try {
                getContentResolver().delete(
                        MediaStore.Files.getContentUri("external"),
                        MediaStore.MediaColumns.DATA + "=?",
                        new String[]{originalPath});
            } catch (Exception ignored) {}
        }
    }

    private void writeSidecar(File mediaFile, String originalPath) {
        File sidecar = new File(trashDir, mediaFile.getName() + ".txt");
        try (PrintWriter pw = new PrintWriter(new FileOutputStream(sidecar))) {
            pw.println(originalPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void undoTrash() {
        if (lastTrashedFile == null || !lastTrashedFile.exists()) {
            Toast.makeText(this, "No hay nada que deshacer", Toast.LENGTH_SHORT).show();
            return;
        }

        File destination = new File(lastOriginalPath);
        File parentDir = destination.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        boolean restored = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                java.nio.file.Files.move(lastTrashedFile.toPath(), destination.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                restored = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (!restored) {
            restored = copyFileLegacy(lastTrashedFile, destination);
            if (restored) lastTrashedFile.delete();
        }

        if (restored) {
            // Delete sidecar
            File sidecar = new File(trashDir, lastTrashedFile.getName() + ".txt");
            if (sidecar.exists()) sidecar.delete();

            // Re-scan into MediaStore
            MediaScannerConnection.scanFile(this,
                    new String[]{lastOriginalPath}, null, null);

            // Re-insert file into the list at currentIndex
            mediaPaths.add(currentIndex, lastOriginalPath);

            // Undo stats
            int deleted = Math.max(0, prefs.getInt(PREF_DELETED, 0) - 1);
            long bytes  = Math.max(0L, prefs.getLong(PREF_BYTES, 0L) - destination.length());
            prefs.edit()
                    .putInt(PREF_DELETED, deleted)
                    .putLong(PREF_BYTES, bytes)
                    .apply();

            lastTrashedFile  = null;
            lastOriginalPath = null;

            updateStatsBar();
            updateTrashBadge();
            showCurrent();
            Toast.makeText(this, "Restaurado", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No se pudo restaurar", Toast.LENGTH_SHORT).show();
        }
    }

    // ═══════════════════════════════════════════════
    //  DISPLAY / UI HELPERS
    // ═══════════════════════════════════════════════

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
            File f = new File(path);
            long modified = f.lastModified();
            SimpleDateFormat sdf = new SimpleDateFormat("MMMM yyyy", new Locale("es"));
            String label = sdf.format(new Date(modified));
            // Capitalize first letter
            if (!label.isEmpty()) {
                label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
            }
            tvMonth.setText(label);
        } catch (Exception e) {
            tvMonth.setText("");
        }
    }

    private void updateStatsBar() {
        int kept    = prefs.getInt(PREF_KEPT, 0);
        int deleted = prefs.getInt(PREF_DELETED, 0);
        long bytes  = prefs.getLong(PREF_BYTES, 0L);
        tvStats.setText("✓ " + kept + "   🗑 " + deleted + "   💾 " + formatMB(bytes));
    }

    private String formatMB(long bytes) {
        double mb = bytes / 1024.0 / 1024.0;
        return String.format(Locale.getDefault(), "%.1f MB", mb);
    }

    private void updateTrashBadge() {
        if (trashDir == null) return;
        File[] files = trashDir.listFiles();
        int count = 0;
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".txt")) count++;
            }
        }
        if (tvTrashCount != null) {
            tvTrashCount.setText("🗑 " + count);
        }
    }

    private void openTrash() {
        startActivity(new Intent(this, TrashActivity.class));
    }

    // ═══════════════════════════════════════════════
    //  FOLDER LOADING
    // ═══════════════════════════════════════════════

    private void loadMediaFromPath(String folderPath, int startIndex) {
        mediaPaths.clear();
        currentIndex = 0;
        currentFolderPath = folderPath;
        positionSaved = false;

        scanFolder(new File(folderPath));

        // Sort by last modified, newest first
        mediaPaths.sort((a, b) -> Long.compare(new File(b).lastModified(), new File(a).lastModified()));

        if (mediaPaths.isEmpty()) {
            Toast.makeText(this, "No hay fotos/videos en esa carpeta", Toast.LENGTH_SHORT).show();
            tvEmpty.setVisibility(View.VISIBLE);
            cardView.setVisibility(View.GONE);
            tvMonth.setText("");
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
                n.endsWith(".avi") || n.endsWith(".webm")) {
                mediaPaths.add(f.getAbsolutePath());
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  PERMISSIONS & FOLDER PICKER
    // ═══════════════════════════════════════════════

    private void requestPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] perms = {
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
            boolean granted = true;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (!granted) {
                ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }, PERMISSION_REQUEST);
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
            else Toast.makeText(this, "Se necesitan permisos de almacenamiento", Toast.LENGTH_LONG).show();
        }
    }

    // ═══════════════════════════════════════════════
    //  CONTINUE SESSION DIALOG
    // ═══════════════════════════════════════════════

    private void checkSavedSession() {
        String savedFolder = prefs.getString(PREF_FOLDER, "");
        int savedIndex = prefs.getInt(PREF_INDEX, 0);
        if (savedFolder.isEmpty() || savedIndex <= 0) return;

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_continue);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setCancelable(false);

        TextView tvInfo   = dialog.findViewById(R.id.tvDialogInfo);
        TextView tvFolder = dialog.findViewById(R.id.tvDialogFolder);
        tvInfo.setText("Quedaste en la foto " + (savedIndex + 1));
        tvFolder.setText(savedFolder);

        dialog.findViewById(R.id.btnContinue).setOnClickListener(v -> {
            dialog.dismiss();
            loadMediaFromPath(savedFolder, savedIndex);
        });
        dialog.findViewById(R.id.btnNewFolder).setOnClickListener(v -> {
            dialog.dismiss();
            prefs.edit().remove(PREF_FOLDER).remove(PREF_INDEX).apply();
        });
        dialog.show();
    }

    // ═══════════════════════════════════════════════
    //  EXIT DIALOG
    // ═══════════════════════════════════════════════

    private void onExitPressed() {
        if (mediaPaths.isEmpty()) { finish(); return; }
        if (positionSaved) { finish(); return; }

        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_exit);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.setCancelable(true);

        TextView tvInfo = dialog.findViewById(R.id.tvExitInfo);
        tvInfo.setText("Vas en la foto " + (currentIndex + 1) + " de " + mediaPaths.size());

        dialog.findViewById(R.id.btnExitSave).setOnClickListener(v -> {
            savePosition();
            dialog.dismiss();
            finish();
        });
        dialog.findViewById(R.id.btnExitNoSave).setOnClickListener(v -> {
            dialog.dismiss();
            finish();
        });
        dialog.show();
    }

    private void savePosition() {
        prefs.edit()
                .putString(PREF_FOLDER, currentFolderPath)
                .putInt(PREF_INDEX, currentIndex)
                .apply();
        positionSaved = true;
    }

    // ═══════════════════════════════════════════════
    //  UTILITIES
    // ═══════════════════════════════════════════════

    private boolean isVideo(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".3gp")
                || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".webm");
    }

    private boolean copyFileLegacy(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void onBackPressed() { onExitPressed(); }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null) videoView.pause();
    }
}
