package com.photoswipe;

import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TrashActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TrashAdapter adapter;
    private List<File> trashedFiles;
    private TextView tvTrashSize;
    private File trashDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trash);

        trashDir = new File(getExternalFilesDir(null), ".trash");
        if (!trashDir.exists()) trashDir.mkdirs();

        // Back button
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        tvTrashSize = findViewById(R.id.tvTrashSize);

        Button btnEmpty = findViewById(R.id.btnEmptyTrash);
        btnEmpty.setOnClickListener(v -> confirmEmptyTrash());

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));

        loadTrashFiles();
    }

    private void loadTrashFiles() {
        trashedFiles = new ArrayList<>();

        File[] files = trashDir.listFiles();
        if (files != null) {
            // Filter out .txt sidecar files — only show media files
            for (File f : files) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".txt")) {
                    trashedFiles.add(f);
                }
            }
            // Sort newest first
            trashedFiles.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        }

        adapter = new TrashAdapter(trashedFiles, new TrashAdapter.TrashCallback() {
            @Override
            public void onRestore(File file) {
                restoreFile(file);
            }

            @Override
            public void onDelete(File file) {
                permanentlyDelete(file);
            }
        });

        recyclerView.setAdapter(adapter);
        updateTrashSize();
    }

    private void updateTrashSize() {
        long totalBytes = 0;
        for (File f : trashedFiles) {
            totalBytes += f.length();
        }
        double mb = totalBytes / 1024.0 / 1024.0;
        String count = trashedFiles.size() + (trashedFiles.size() == 1 ? " archivo" : " archivos");
        tvTrashSize.setText(String.format(Locale.getDefault(), "%s  •  %.1f MB", count, mb));
    }

    private void restoreFile(File trashFile) {
        String originalPath = readSidecar(trashFile);
        if (originalPath == null || originalPath.isEmpty()) {
            Toast.makeText(this, "No se encontró la ruta original", Toast.LENGTH_SHORT).show();
            return;
        }

        File destination = new File(originalPath);
        File parentDir = destination.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Files.move(trashFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                if (!copyFile(trashFile, destination)) {
                    Toast.makeText(this, "Error al restaurar", Toast.LENGTH_SHORT).show();
                    return;
                }
                trashFile.delete();
            }

            // Delete sidecar
            File sidecar = sidecarFor(trashFile);
            if (sidecar.exists()) sidecar.delete();

            // Re-scan into MediaStore
            MediaScannerConnection.scanFile(this, new String[]{destination.getAbsolutePath()}, null, null);

            updateTrashSize();
            Toast.makeText(this, "Restaurado: " + destination.getName(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error al restaurar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void permanentlyDelete(File trashFile) {
        File sidecar = sidecarFor(trashFile);
        boolean deleted = trashFile.delete();
        if (sidecar.exists()) sidecar.delete();

        if (deleted) {
            updateTrashSize();
            Toast.makeText(this, "Eliminado permanentemente", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmEmptyTrash() {
        if (trashedFiles.isEmpty()) {
            Toast.makeText(this, "La papelera ya está vacía", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Vaciar papelera")
                .setMessage("¿Eliminar permanentemente " + trashedFiles.size() + " archivo(s)? Esta acción no se puede deshacer.")
                .setPositiveButton("Vaciar", (dialog, which) -> emptyTrash())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void emptyTrash() {
        File[] allFiles = trashDir.listFiles();
        if (allFiles != null) {
            for (File f : allFiles) {
                f.delete();
            }
        }
        trashedFiles.clear();
        adapter.notifyDataSetChanged();
        updateTrashSize();
        Toast.makeText(this, "Papelera vaciada", Toast.LENGTH_SHORT).show();
    }

    /** Reads the sidecar .txt file next to a trash file to get the original path. */
    private String readSidecar(File mediaFile) {
        File sidecar = sidecarFor(mediaFile);
        if (!sidecar.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(sidecar))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /** Returns the expected sidecar file for a given media file in trash. */
    private File sidecarFor(File mediaFile) {
        return new File(trashDir, mediaFile.getName() + ".txt");
    }

    private boolean copyFile(File src, File dst) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
