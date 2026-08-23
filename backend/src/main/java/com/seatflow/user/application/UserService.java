package com.seatflow.user.application;

import com.seatflow.common.exception.ApiException;
import com.seatflow.common.exception.ErrorCode;
import com.seatflow.user.domain.User;
import com.seatflow.user.infrastructure.UserRepository;
import com.seatflow.user.presentation.dto.AuthDtos.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read access to accounts.
 * <p>
 * Kept apart from {@link AuthService} so that credential handling and ordinary
 * profile queries do not accumulate in one class.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserResponse findById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No account exists with that identifier."));

        List<String> roles = user.getRoles().stream()
                .map(role -> role.getName().name())
                .sorted()
                .toList();

        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), roles);
    }
}
