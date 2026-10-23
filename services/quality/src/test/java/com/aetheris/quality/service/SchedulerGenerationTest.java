package com.aetheris.quality.service;
import com.aetheris.quality.entity.QualityRule;
import org.junit.jupiter.api.Test;
import org.quartz.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class SchedulerGenerationTest {
    @Test void everyScheduledJobCarriesDefinitionGeneration() throws Exception {
        Scheduler quartz = mock(Scheduler.class);
        QualityRule definition = new QualityRule(); definition.setId(1L); definition.setName("test");
        definition.setStatus("enabled"); definition.setCronExpression("0 0 2 * * ?");
        new QualitySchedulerService(quartz).sync(definition);
        ArgumentCaptor<JobDetail> job = ArgumentCaptor.forClass(JobDetail.class);
        verify(quartz).scheduleJob(job.capture(), any(Trigger.class));
        assertTrue(job.getValue().getJobDataMap().containsKey("scheduleVersion"));
    }
}