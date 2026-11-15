package vingo.search.internal;

import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import vingo.support.IntegrationTest;

/**
 * Base cho các test cache của search cần spy repository.
 *
 * <p>Spring lấy tập bean bị spy làm một phần khoá của context cache: hai class spy hai bean khác nhau
 * sẽ dựng hai context riêng. Khai báo chung ở đây để các test này dùng chung một context.
 */
abstract class SearchRepositorySpyTest extends IntegrationTest {

    @MockitoSpyBean
    protected SeatmapRepository seatmaps;

    @MockitoSpyBean
    protected EventDetailRepository details;
}
