package com.king.ai.compliance;

import com.king.ai.compliance.dto.ComplianceSettingUpdateRequest;
import com.king.ai.compliance.dto.ComplianceSettingView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplianceSettingControllerTest {

    @Test
    void getReturnsServiceView() {
        ComplianceSettingsService service = mock(ComplianceSettingsService.class);
        when(service.view(1L)).thenReturn(new ComplianceSettingView(true, "LOCAL",
                "BLOCK", "WARN", List.of("违禁词"), List.of("EMP-\\d+")));
        ComplianceSettingController controller = new ComplianceSettingController(service);

        ComplianceSettingView view = controller.get(1L).data();

        assertThat(view.moderationMode()).isEqualTo("LOCAL");
        assertThat(view.blocklist()).containsExactly("违禁词");
    }

    @Test
    void updateDelegatesToService() {
        ComplianceSettingsService service = mock(ComplianceSettingsService.class);
        ComplianceSettingUpdateRequest request = new ComplianceSettingUpdateRequest(
                true, "LOCAL", "BLOCK", "BLOCK", "", "");
        ComplianceSettingView expected = new ComplianceSettingView(true, "LOCAL",
                "BLOCK", "BLOCK", List.of(), List.of());
        when(service.update(1L, request)).thenReturn(expected);
        ComplianceSettingController controller = new ComplianceSettingController(service);

        assertThat(controller.update(1L, request).data()).isEqualTo(expected);
    }
}
