import AsyncStorage from '@react-native-async-storage/async-storage'                                            
                                                                                                                
const REMINDERS_STORAGE_KEY = '@reminders_list'                                                                 
                                                                                                                
export interface Reminder {                                                                                     
  id: string // Unique ID for the reminder itself                                                               
  task: string // Description of the task                                                                       
  triggerTimestamp: number // Milliseconds since epoch when it should trigger                                   
  scheduledNotificationId?: string // ID returned by the notification library                                   
  createdAt: number // Timestamp when the reminder was created                                                  
}                                                                                                               
                                                                                                                
/**                                                                                                             
 * Retrieves all reminders from AsyncStorage.                                                                   
 */                                                                                                             
export const getAllReminders = async (): Promise<Reminder[]> => {                                               
  try {                                                                                                         
    const remindersJson = await AsyncStorage.getItem(REMINDERS_STORAGE_KEY)                                     
    return remindersJson ? JSON.parse(remindersJson) : []                                                       
  } catch (error) {                                                                                             
    console.error('Failed to load reminders:', error)                                                           
    return []                                                                                                   
  }                                                                                                             
}                                                                                                               
                                                                                                                
/**                                                                                                             
 * Saves a new reminder or updates an existing one in AsyncStorage.                                             
 */                                                                                                             
export const saveReminder = async (reminder: Reminder): Promise<void> => {                                      
  try {                                                                                                         
    const existingReminders = await getAllReminders()                                                           
    const index = existingReminders.findIndex((r) => r.id === reminder.id)                                      
    if (index > -1) {                                                                                           
      // Update existing reminder                                                                               
      existingReminders[index] = reminder                                                                       
    } else {                                                                                                    
      // Add new reminder                                                                                       
      existingReminders.push(reminder)                                                                          
    }                                                                                                           
    await AsyncStorage.setItem(                                                                                 
      REMINDERS_STORAGE_KEY,                                                                                    
      JSON.stringify(existingReminders),                                                                        
    )                                                                                                           
  } catch (error) {                                                                                             
    console.error('Failed to save reminder:', error)                                                            
  }                                                                                                             
}                                                                                                               
                                                                                                                
/**                                                                                                             
 * Deletes a reminder by its ID from AsyncStorage.                                                              
 */                                                                                                             
export const deleteReminder = async (id: string): Promise<void> => {                                            
  try {                                                                                                         
    const existingReminders = await getAllReminders()                                                           
    const updatedReminders = existingReminders.filter((r) => r.id !== id)                                       
    await AsyncStorage.setItem(                                                                                 
      REMINDERS_STORAGE_KEY,                                                                                    
      JSON.stringify(updatedReminders),                                                                         
    )                                                                                                           
  } catch (error) {                                                                                             
    console.error('Failed to delete reminder:', error)                                                          
  }                                                                                                             
}   