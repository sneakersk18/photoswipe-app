package com.photoswipe;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.bumptech.glide.Glide;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ImageView imageView;
    private TextView tvCounter, tvHint;
    private Button btnSelectFolder;
    private GestureDetector gestureDetector;
    private List<String> mediaPaths = new ArrayList<>();
    private int currentIndex = 0;
    private static final int PERMISSION_REQUEST = 100;
    private static final int FOLDER_PICK_REQUEST = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        tvCounter = findViewById(R.id.tvCounter);
        tvHint = findViewById(R.id.tvHint);
        btnSelectFolder = findViewById(R.id.btnSelectFolder);

        gestureDetector = new GestureDetector(this, new SwipeListener());
        imageView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });

        btnSelectFolder.setOnClickListener(v -> requestPermissionsAndPick());
    }

    private void requestPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] perms = {Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
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
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
                return;
            }
        }
        openFolderPicker();
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, FOLDER_PICK_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FOLDER_PICK_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            loadMediaFromFolder(treeUri);
        }
    }

    private void loadMediaFromFolder(Uri treeUri) {
        mediaPaths.clear();
        currentIndex = 0;

        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        String[] split = docId.split(":");
        String type = split[0];
        String path = split.length > 1 ? split[1] : "";

        String fullPath;
        if ("primary".equalsIgnoreCase(type)) {
            fullPath = Environment.getExternalStorageDirectory() + "/" + path;
        } else {
            fullPath = "/storage/" + type + "/" + path;
        }

        File folder = new File(fullPath);
        if (folder.exists()) {
            scanFolder(folder);
        }

        if (mediaPaths.isEmpty()) {
            Toast.makeText(this, "No hay fotos/videos en esa carpeta", Toast.LENGTH_SHORT).show();
        } else {
            btnSelectFolder.setText("Cambiar carpeta");
            tvHint.setText("← Borrar    |    Guardar →");
            showCurrent();
        }
    }

    private void scanFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".webp") ||
                    name.endsWith(".mp4") || name.endsWith(".mkv") ||
                    name.endsWith(".3gp") || name.endsWith(".mov")) {
                    mediaPaths.add(f.getAbsolutePath());
                }
            }
        }
    }

    private void showCurrent() {
        if (mediaPaths.isEmpty() || currentIndex >= mediaPaths.size()) {
            tvCounter.setText("¡Listo! No hay más archivos");
            imageView.setImageDrawable(null);
            return;
        }
        String path = mediaPaths.get(currentIndex);
        tvCounter.setText((currentIndex + 1) + " / " + mediaPaths.size());
        Glide.with(this).load(new File(path)).into(imageView);
    }

    private void keepFile() {
        Toast.makeText(this, "✓ Guardado", Toast.LENGTH_SHORT).show();
        currentIndex++;
        showCurrent();
    }

    private void deleteFile() {
        String path = mediaPaths.get(currentIndex);
        File file = new File(path);
        if (file.delete()) {
            Toast.makeText(this, "🗑 Eliminado", Toast.LENGTH_SHORT).show();
            mediaPaths.remove(currentIndex);
            showCurrent();
        } else {
            Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show();
            currentIndex++;
            showCurrent();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST) {
            boolean allGranted = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            if (allGranted) openFolderPicker();
            else Toast.makeText(this, "Se necesitan permisos para acceder a archivos", Toast.LENGTH_LONG).show();
        }
    }

    private class SwipeListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY = 100;

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
            if (mediaPaths.isEmpty()) return false;
            float diffX = e2.getX() - e1.getX();
            if (Math.abs(diffX) > Math.abs(e2.getY() - e1.getY())) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(vX) > SWIPE_VELOCITY) {
                    if (diffX > 0) keepFile();
                    else deleteFile();
                    return true;
                }
            }
            return false;
        }
    }
}
