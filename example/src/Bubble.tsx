import React, { useContext } from 'react'
import type { ReactNode } from 'react'
import { View, Text, TouchableOpacity } from 'react-native'
import Clipboard from '@react-native-clipboard/clipboard'
import { ThemeContext, UserContext } from '@flyerhq/react-native-chat-ui'
import type { MessageType } from '@flyerhq/react-native-chat-ui'

export const Bubble = ({
  child,
  message,
}: {
  child: ReactNode
  message: MessageType.Any
}) => {
  const theme = useContext(ThemeContext)
  const user = useContext(UserContext)
  const currentUserIsAuthor = user?.id === message.author.id
  const { copyable, timings, system } = message.metadata || {}
  const isAssistant = message.author.id === 'h3o3lc5xj' && !system

  const Container: React.ComponentClass<any> = copyable ? TouchableOpacity : View
  return (
    <Container
      style={{
        backgroundColor: isAssistant 
          ? '#2f9153' // Assistant message color
          : currentUserIsAuthor && message.type !== 'image'
            ? theme.colors.primary
            : theme.colors.secondary,
        borderBottomLeftRadius: currentUserIsAuthor
          ? theme.borders.messageBorderRadius
          : 0,
        borderBottomRightRadius: currentUserIsAuthor
          ? 0
          : theme.borders.messageBorderRadius,
        borderColor: 'transparent',
        borderRadius: theme.borders.messageBorderRadius,
        overflow: 'hidden',
      }}
      onPress={() => {
        if (message.type !== 'text') return
        Clipboard.setString(message.text);
      }}
    >
      {child}
      {timings && (
        <Text
          style={{
            textAlign: 'right',
            color: '#E8EBF1',
            paddingRight: 12,
            paddingBottom: 12,
            marginTop: -8,
            fontSize: 10,
          }}
        >
          {timings}
        </Text>
      )}
    </Container>
  )
}
