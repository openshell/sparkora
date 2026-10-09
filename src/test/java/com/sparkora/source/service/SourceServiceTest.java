package com.sparkora.source.service;

import com.sparkora.domain.dto.ChannelDTO;
import com.sparkora.domain.dto.SourceCreateDTO;
import com.sparkora.domain.entity.SourceChannelEntity;
import com.sparkora.domain.entity.SourceEntity;
import com.sparkora.mapper.SourceChannelMapper;
import com.sparkora.mapper.SourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 信源注册闭环服务单测(10-09-cpca-gasgoo-collection G4,AC-6):
 * create 事务插 source+channels 并校验 type/listUrl/parseRules;栏目 CRUD 触发调度重注册。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceServiceTest {

    @Mock SourceMapper sourceMapper;
    @Mock SourceChannelMapper channelMapper;
    @Mock SourceScheduleService scheduleService;

    private SourceService service;

    @BeforeEach
    void setUp() {
        service = new SourceService(sourceMapper, channelMapper, scheduleService);
    }

    private SourceCreateDTO createDto() {
        SourceCreateDTO dto = new SourceCreateDTO();
        dto.setName("乘联会");
        dto.setType("SITE");
        dto.setNeedCrawl4ai(true);
        dto.setEnabled(false);
        ChannelDTO c = new ChannelDTO();
        c.setName("车市解读");
        c.setListUrl("https://www.cpcaauto.com/news.php?types=csjd");
        c.setDetailBaseUrl("https://www.cpcaauto.com");
        c.setCategory("销量数据");
        c.setParseRules("{\"list\":\".list_d li.q\",\"detail\":\"div.read_content\"}");
        dto.setChannels(List.of(c));
        return dto;
    }

    @Test
    void create_插入源与栏目_回读详情() {
        SourceEntity stored = new SourceEntity();
        stored.setId(20L);
        stored.setName("乘联会");
        stored.setType("SITE");
        when(sourceMapper.selectById(20L)).thenReturn(stored);
        SourceChannelEntity ch = new SourceChannelEntity();
        ch.setId(30L);
        ch.setName("车市解读");
        ch.setSourceId(20L);
        when(channelMapper.selectList(any())).thenReturn(List.of(ch));
        // 模拟 insert 后回填自增 id
        org.mockito.Mockito.doAnswer(inv -> {
            SourceEntity s = inv.getArgument(0);
            s.setId(20L);
            return 1;
        }).when(sourceMapper).insert(any(SourceEntity.class));

        SourceEntity created = service.create(createDto());

        assertEquals(20L, created.getId());
        assertEquals(1, created.getChannels().size());
        assertEquals("车市解读", created.getChannels().get(0).getName());
        verify(scheduleService).register(any(SourceEntity.class));
    }

    @Test
    void create_类型非法_抛400() {
        SourceCreateDTO dto = createDto();
        dto.setType("BAD");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.create(dto));
        assertTrue(ex.getMessage().contains("RSS"));
    }

    @Test
    void create_栏目缺listUrl_抛400() {
        SourceCreateDTO dto = createDto();
        dto.getChannels().get(0).setListUrl(" ");
        assertThrows(IllegalArgumentException.class, () -> service.create(dto));
    }

    @Test
    void create_parseRules非法JSON_抛400() {
        SourceCreateDTO dto = createDto();
        dto.getChannels().get(0).setParseRules("{ 非法");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.create(dto));
        assertTrue(ex.getMessage().contains("JSON"));
    }

    @Test
    void addChannel_源不存在_抛400() {
        when(sourceMapper.selectById(99L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.addChannel(99L, new ChannelDTO()));
    }

    @Test
    void deleteChannel_不存在_抛400() {
        when(channelMapper.selectById(999L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.deleteChannel(999L));
    }

    @Test
    void updateChannel_置空listUrl_抛400() {
        SourceChannelEntity existing = new SourceChannelEntity();
        existing.setId(31L);
        existing.setSourceId(20L);
        when(channelMapper.selectById(31L)).thenReturn(existing);
        ChannelDTO dto = new ChannelDTO();
        dto.setListUrl("  ");
        assertThrows(IllegalArgumentException.class, () -> service.updateChannel(31L, dto));
    }
}
