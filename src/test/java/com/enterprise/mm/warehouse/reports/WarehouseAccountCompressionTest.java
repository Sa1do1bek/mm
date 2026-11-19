package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.common.*;
import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarehouseAccountCompressionTest {
    final UUID company=UUID.randomUUID(),run=UUID.randomUUID();
    @BeforeEach void setup(){TenantContext.setTenant("compression");CompanyContext.setCompany(company);}
    @AfterEach void cleanup(){TenantContext.clear();CompanyContext.clear();}
    byte[] gzip(byte[] value)throws IOException {var out=new ByteArrayOutputStream();try(var zip=new GZIPOutputStream(out)){zip.write(value);}return out.toByteArray();}
    @Test void compressedRequestsUseTheSameValidatedTransactionalImporter()throws Exception {
        var service=mock(WarehouseAccountImportService.class);var mapper=new ObjectMapper();var controller=new WarehouseAccountImportController(service,mapper);
        var chunk=new WarehouseAccountImportDtos.Chunk(List.of(new WarehouseAccountImportDtos.Row(1,"a".repeat(64),"source")));
        when(service.chunk("compression",company,run,chunk)).thenReturn(Map.of("acceptedRows",1));
        controller.compressedChunk(run,gzip(mapper.writeValueAsBytes(chunk)));verify(service).chunk("compression",company,run,chunk);
    }
    @Test void decompressionBombsAndMalformedBodiesCannotReachTheImporter()throws Exception {
        var service=mock(WarehouseAccountImportService.class);var controller=new WarehouseAccountImportController(service,new ObjectMapper());
        assertThatThrownBy(()->controller.compressedChunk(run,gzip(new byte[WarehouseAccountImportController.MAX_REQUEST_BYTES+1]))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->controller.compressedChunk(run,new byte[]{1,2,3})).isInstanceOf(BadRequestException.class);verifyNoInteractions(service);
    }
}
