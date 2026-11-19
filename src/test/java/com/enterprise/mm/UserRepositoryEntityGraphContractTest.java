package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UserRepositoryEntityGraphContractTest {

    @Test
    void userRepositoryEntityGraphsPreserveEagerRoleLoading() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/enterprise/mm/repository/UserRepository.java"));

        assertThat(Pattern.compile("@EntityGraph\\((?![^)]*EntityGraphType\\.LOAD)")
            .matcher(source)
            .find())
            .isFalse();
    }
}
