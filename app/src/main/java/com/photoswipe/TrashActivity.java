package com.photoswipe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class TrashActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TrashAdapter adapter;
    private List<File> trashedFiles;
    private TextView tvTrashSize;
    private SharedPreferences prefs;
    private static final int DELETE_REQUEST = 400;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trash);

        prefs = getSharedPreferences("photoswipe", MODE_PRIVATE);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        tvTrashSize = findViewById(R.id.tvTrashSize);

        Button btnEmpty = findViewById(R.id.btnEmptyTrash);
        btnEmpty.setOnClickListener(v -> confirmEmptyTrash());

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));

        loadTrashFiles();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DELETE_REQUEST && resultCode == Activity.RESULT_OK) {
            // System confirmed deletion — clear trash list
            prefs.edit().putString(MainActivity.PREF_TRASH, "").apply();
            trashedFiles.clear();
            adapter.notifyDataSetChanged();
            updateTrashSize();
            Toast.makeText(this, "Papelera vaciada", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadTrashFiles() {
        trashedFiles = new ArrayList<>();
        String trashPaths = prefs.getString(MainActivity.PREF_TRASH, "");
        if (!trashPaths.isEmpty()) {
            for (String path : trashPaths.split("\\|")) {
                File f = new File(path);
                if (f.exists()) trashedFiles.add(f);
            }
        }

        adapter = new TrashAdapter(trashedFiles, new TrashAdapter.TrashCallback() {
            @Override public void onRestore(File file) { restoreFile(file); }
            @Override public void onDelete(File file) { deleteSingle(file); }
        });

        recyclerView.setAdapter(adapter);
        updateTrashSize();
    }

    private void updateTrashSize() {
        long totalBytes = 0;
        for (File f : trashedFiles) totalBytes += f.length();
        double mb = totalBytes / 1024.0 / 1024.0;
        String count = trashedFiles.size() + (trashedFiles.size() == 1 ? " archivo" : " archivos");
        tvTrashSize.setText(String.format(Locale.getDefault(), "%s  •  %.1f MB", count, mb));
    }

    private void restoreFile(File file) {
        // Just remove from trash list — file was never moved
        removeFromTrashList(file.getAbsolutePath());
        trashedFiles.remove(file);
        adapter.notifyDataSetChanged();
        updateTrashSize();
        Toast.makeText(this, "Restaurado: " + file.getName(), Toast.LENGTH_SHORT).show();
    }

    private void deleteSingle(File file) {
        new AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Eliminar")
            .setMessage("¿Eliminar permanentemente " + file.getName() + "?")
            .setPositiveButton("Eliminar", (d, w) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Uri uri = getMediaUri(file.getAbsolutePath());
                    if (uri != null) {
                        try {
                            List<Uri> uris = new ArrayList<>();
                            uris.add(uri);
                            PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                            startIntentSenderForResult(pi.getIntentSender(), DELETE_REQUEST + 1, null, 0, 0, 0);
                            removeFromTrashList(file.getAbsolutePath());
                            trashedFiles.remove(file);
                            adapter.notifyDataSetChanged();
                            updateTrashSize();
                            return;
                        } catch (IntentSender.SendIntentException e) { e.printStackTrace(); }
                    }
                }
                // API 29- or fallback
                if (file.delete()) {
                    removeFromTrashList(file.getAbsolutePath());
                    trashedFiles.remove(file);
                    adapter.notifyDataSetChanged();
                    updateTrashSize();
                    android.media.MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
                } else {
                    Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void confirmEmptyTrash() {
        if (trashedFiles.isEmpty()) {
            Toast.makeText(this, "La papelera ya está vacía", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Vaciar papelera")
            .setMessage("¿Eliminar permanentemente " + trashedFiles.size() + " archivo(s)?\nEsta acción no se puede deshacer.")
            .setPositiveButton("Vaciar", (dialog, which) -> emptyTrash())
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void emptyTrash() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: one system dialog for all files
            List<Uri> uris = new ArrayList<>();
            for (File f : trashedFiles) {
                Uri uri = getMediaUri(f.getAbsolutePath());
                if (uri != null) uris.add(uri);
            }
            if (!uris.isEmpty()) {
                try {
                    PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                    startIntentSenderForResult(pi.getIntentSender(), DELETE_REQUEST, null, 0, 0, 0);
                    return;
                } catch (IntentSender.SendIntentException e) { e.printStackTrace(); }
            }
        }

        // API 29- with legacy storage: direct delete
        int deleted = 0;
        for (File f : new ArrayList<>(trashedFiles)) {
            if (f.delete()) {
                android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
                deleted++;
            }
        }
        prefs.edit().putString(MainActivity.PREF_TRASH, "").apply();
        trashedFiles.clear();
        adapter.notifyDataSetChanged();
        updateTrashSize();
        Toast.makeText(this, deleted + " archivo(s) eliminado(s)", Toast.LENGTH_SHORT).show();
    }

    private void removeFromTrashList(String path) {
        String existing = prefs.getString(MainActivity.PREF_TRASH, "");
        if (existing.isEmpty()) return;
        List<String> list = new ArrayList<>(Arrays.asList(existing.split("\\|")));
        list.remove(path);
        prefs.edit().putString(MainActivity.PREF_TRASH, android.text.TextUtils.join("|", list)).apply();
    }

    private Uri getMediaUri(String path) {
        String[] proj = {MediaStore.MediaColumns._ID};
        String sel = MediaStore.MediaColumns.DATA + "=?";
        Uri[] cols = {MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI};
        for (Uri col : cols) {
            try (Cursor c = getContentResolver().query(col, proj, sel, new String[]{path}, null)) {
                if (c != null && c.moveToFirst())
                    return ContentUris.withAppendedId(col, c.getLong(0));
            }
        }
        return null;
    }
}
