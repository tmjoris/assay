package io.github.tmjoris.assay.compare;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.util.Text;

/** One engine's answer to one query, loaded from the Arrow IPC file its worker wrote. */
public record ResultSet(List<String> types, List<List<Object>> rows) {

    public static final String TYPE_METADATA = "spark_type";

    public static ResultSet read(Path file) throws IOException {
        try (BufferAllocator allocator = new RootAllocator();
                FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
                ArrowFileReader reader = new ArrowFileReader(channel, allocator)) {
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            List<String> types = new ArrayList<>();
            for (Field f : root.getSchema().getFields()) {
                types.add(f.getMetadata().getOrDefault(TYPE_METADATA, f.getType().toString()));
            }
            List<List<Object>> rows = new ArrayList<>();
            while (reader.loadNextBatch()) {
                for (int i = 0; i < root.getRowCount(); i++) {
                    List<Object> row = new ArrayList<>(root.getFieldVectors().size());
                    for (FieldVector v : root.getFieldVectors()) {
                        Object value = v.getObject(i);
                        row.add(value instanceof Text t ? t.toString() : value);
                    }
                    rows.add(row);
                }
            }
            return new ResultSet(List.copyOf(types), rows);
        }
    }
}
