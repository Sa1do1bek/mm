package com.enterprise.mm.warehouse.history;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.warehouse.history.WarehouseHistoryDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WarehouseHistoryCompressionTest {
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    WarehouseHistoryImportService service=mock(WarehouseHistoryImportService.class);
    WarehouseHistoryImportController controller=new WarehouseHistoryImportController(mock(WarehouseHistoryProjectionVerification.class),service,mapper);
    UUID company=UUID.randomUUID(),run=UUID.randomUUID();
    byte[] gzip(byte[] value) throws IOException {
        var bytes=new ByteArrayOutputStream();try(var output=new GZIPOutputStream(bytes)){output.write(value);}return bytes.toByteArray();
    }
    Chunk source() {
        return new Chunk(List.of(new Row(1,"a".repeat(64),"{\"quantity\":\"-0.1234567\",\"documentType\":\"Перемещение\",\"data\":{}}")));
    }
    @Test void compressedAndJsonRoutesPassIdenticalContentToTheValidatedService() throws Exception {
        var source=source();byte[] raw=mapper.writeValueAsBytes(source);
        when(service.chunk(eq("test"),eq(company),eq(run),eq(source))).thenReturn(new ChunkResult(1,1,"b".repeat(64),"c".repeat(64)));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();String path="/api/migration/warehouse-history/runs/"+run+"/chunks";
        mvc.perform(post(path).header("X-Tenant-Id","test").header("X-Company-Id",company).contentType("application/gzip").content(gzip(raw))).andExpect(status().isOk());
        mvc.perform(post(path).header("X-Tenant-Id","test").header("X-Company-Id",company).contentType("application/json").content(raw)).andExpect(status().isOk());
        verify(service,times(2)).chunk("test",company,run,source);
    }
    @Test void invalidGzipAndJsonCannotReachTheImportService() throws Exception {
        assertThatThrownBy(()->controller.compressedChunk("test",company,run,new byte[]{1,2,3})).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->controller.compressedChunk("test",company,run,gzip("invalid JSON".getBytes()))).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(service);
    }
    @Test void expandedAndCompressedSizesAreBoundedBeforeImport() throws Exception {
        byte[] oversized=new byte[WarehouseHistoryImportController.MAX_REQUEST_BYTES+1];Arrays.fill(oversized,(byte)' ');
        assertThatThrownBy(()->controller.compressedChunk("test",company,run,gzip(oversized))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->controller.compressedChunk("test",company,run,oversized)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(service);
    }
    @Test void unsupportedMediaTypeCannotReachTheImportService() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/migration/warehouse-history/runs/"+run+"/chunks").header("X-Tenant-Id","test").header("X-Company-Id",company).contentType("application/octet-stream").content(new byte[]{1})).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }
}
