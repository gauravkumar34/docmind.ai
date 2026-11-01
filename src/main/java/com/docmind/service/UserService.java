package com.docmind.service;

import com.docmind.dto.RegisterUserRequest;
import com.docmind.dto.UserDto;
import com.docmind.entity.Role;
import com.docmind.entity.User;
import com.docmind.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;


    public UserDto registerUser(RegisterUserRequest registerUserRequest) {

        //validation
        if(registerUserRequest.username().isBlank()){
            throw new IllegalArgumentException("Username cannot be blank");
        }

        if(userRepository.existsByUsername(registerUserRequest.username())){
            throw new IllegalArgumentException("Username already is exists");
        }

        User user = new User();
        user.setUsername(registerUserRequest.username());
        user.setEmail(registerUserRequest.email());
        user.setPassword(passwordEncoder.encode(registerUserRequest.password()));
        user.setRole(Role.USER);

        User savedUser = userRepository.save(user);

        return new UserDto(savedUser.getId(), savedUser.getUsername(), savedUser.getEmail(), savedUser.getRole());

    }
}
