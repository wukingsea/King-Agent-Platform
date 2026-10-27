package com.king.ai.compliance;

import com.king.ai.common.BizException;
import com.king.ai.compliance.dto.ComplianceSettingUpdateRequest;
import com.king.ai.compliance.entity.ComplianceSetting;
import com.king.ai.compliance.mapper.ComplianceSettingMapper;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComplianceSettingsServiceTest {

    @Mock
    private ComplianceSettingMapper mapper;

    private ComplianceSettingsService service;

    @BeforeEach
    void setUp() {
        KingAiProperties properties = new KingAiProperties();
        properties.getCompliance().getPii().setDefaultEnabled(true);
        properties.getCompliance().getModeration().setDefaultMode(ModerationMode.OFF);
        service = new ComplianceSettingsService(mapper, properties);
    }

    @Test
    void effectiveReturnsDefaultsWhenSpaceHasNoRow() {
        when(mapper.selectOne(any())).thenReturn(null);

        ComplianceSettings settings = service.effective(7L);

        assertThat(settings.piiEnabled()).isTrue();
        assertThat(settings.moderationMode()).isEqualTo(ModerationMode.OFF);
        assertThat(settings.inputAction()).isEqualTo(ModerationAction.BLOCK);
        assertThat(settings.outputAction()).isEqualTo(ModerationAction.BLOCK);
        assertThat(settings.blocklist()).isEmpty();
        assertThat(settings.customPiiPatterns()).isEmpty();
    }

    @Test
    void effectiveDefaultsMalformedStoredActionsToBlock() {
        ComplianceSetting row = new ComplianceSetting();
        row.setSpaceId(7L);
        row.setPiiEnabled(1);
        row.setModerationMode("LOCAL");
        row.setModerationInputAction("NOT_AN_ACTION");
        row.setModerationOutputAction("ALSO_NOT_AN_ACTION");
        when(mapper.selectOne(any())).thenReturn(row);

        ComplianceSettings settings = service.effective(7L);

        assertThat(settings.inputAction()).isEqualTo(ModerationAction.BLOCK);
        assertThat(settings.outputAction()).isEqualTo(ModerationAction.BLOCK);
    }

    @Test
    void effectiveCachesPerSpace() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.effective(7L);
        service.effective(7L);

        verify(mapper, times(1)).selectOne(any());
    }

    @Test
    void updateRejectsUnknownMode() {
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "STRICT", "BLOCK", "BLOCK", "", "");

        assertThatThrownBy(() -> service.update(7L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审核模式");
    }

    @Test
    void updateRejectsInvalidCustomRegex() {
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "LOCAL", "BLOCK", "BLOCK", "", "([a-z");

        assertThatThrownBy(() -> service.update(7L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第 1 条");
    }

    @Test
    void updateRejectsUnboundedNestedQuantifierPattern() {
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "LOCAL", "BLOCK", "BLOCK", "", "(a+)+b");

        assertThatThrownBy(() -> service.update(7L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第 1 条")
                .hasMessageContaining("REGEX_LIMIT");
    }

    @Test
    void updateAcceptsBoundedNestedQuantifierPattern() {
        when(mapper.selectOne(any())).thenReturn(null);
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "LOCAL", "BLOCK", "BLOCK", "", "(\\d{1,3}\\.){3}");

        service.update(7L, request);

        ArgumentCaptor<ComplianceSetting> captor = ArgumentCaptor.forClass(ComplianceSetting.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getPiiCustomPatterns()).isEqualTo("(\\d{1,3}\\.){3}");
    }

    @Test
    void updateNormalizesBlocklistAndPersists() {
        when(mapper.selectOne(any())).thenReturn(null);
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "local", "block", "warn",
                " 违禁词 \n违禁词, 另一个词 \n\n", "");

        service.update(7L, request);

        ArgumentCaptor<ComplianceSetting> captor = ArgumentCaptor.forClass(ComplianceSetting.class);
        verify(mapper).insert(captor.capture());
        ComplianceSetting saved = captor.getValue();
        assertThat(saved.getBlocklist()).isEqualTo("违禁词\n另一个词");
        assertThat(saved.getModerationMode()).isEqualTo("LOCAL");
        assertThat(saved.getModerationInputAction()).isEqualTo("BLOCK");
        assertThat(saved.getModerationOutputAction()).isEqualTo("WARN");
        assertThat(saved.getPiiEnabled()).isEqualTo(1);
    }
}
