package com.king.ai.system;

import com.king.ai.common.BizException;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.license.LicenseService;
import com.king.ai.system.dto.SpaceCreateRequest;
import com.king.ai.system.entity.Space;
import com.king.ai.system.mapper.SpaceMapper;
import com.king.ai.system.mapper.UserMapper;
import com.king.ai.system.mapper.UserSpaceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceServiceSoftDeleteTest {

    @Mock
    private SpaceMapper spaceMapper;
    @Mock
    private UserSpaceMapper userSpaceMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private LicenseService licenseService;

    @InjectMocks
    private SpaceService spaceService;

    @Test
    void deleteArchivesSpaceWithoutPurgingEvidence() {
        Space space = new Space();
        space.setId(4L);
        when(spaceMapper.selectById(4L)).thenReturn(space);

        spaceService.delete(admin(), 4L);

        verify(spaceMapper).softDeleteById(4L);
        verify(userSpaceMapper).delete(any());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void createRejectsCodeReservedByArchivedSpace() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any())).thenReturn(1L);

        assertThatThrownBy(() -> spaceService.create(
                new SpaceCreateRequest("新空间", "taken-code", null, 0), 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("空间编码已存在");
    }

    private static UserPrincipal admin() {
        return new UserPrincipal(1L, "admin", "管理员", "ADMIN", null, null, false);
    }
}
