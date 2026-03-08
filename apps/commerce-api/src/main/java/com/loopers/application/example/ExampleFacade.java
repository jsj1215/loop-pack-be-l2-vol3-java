package com.loopers.application.example;

import com.loopers.domain.example.ExampleModel;
import com.loopers.domain.example.ExampleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class ExampleFacade {
    private final ExampleService exampleService;

    public ExampleInfo getExample(Long id) {
        ExampleModel example = exampleService.getExample(id);
        return ExampleInfo.from(example);
    }
}
